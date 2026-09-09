#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
runner="$script_dir/run-local-cdc-delivery-focused-diagnostic.sh"

# shellcheck source=scripts/lib/local-image-inventory.sh
source "$script_dir/lib/local-image-inventory.sh"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$script_dir/lib/local-image-transport.sh"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$script_dir/lib/local-certification-provenance.sh"
# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$script_dir/lib/local-certification-phase-graph.sh"
# shellcheck source=scripts/lib/local-certification-evidence.sh
source "$script_dir/lib/local-certification-evidence.sh"
# shellcheck source=scripts/lib/local-certification-images.sh
source "$script_dir/lib/local-certification-images.sh"
# shellcheck source=scripts/lib/local-certification-focused-diagnostic.sh
source "$script_dir/lib/local-certification-focused-diagnostic.sh"

fail() {
  printf 'Focused CDC diagnostic contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-focused-cdc.XXXXXX")"
trap 'rm -rf -- "$fixture_root"' EXIT
export SIMPLEMATCH_LOCAL_REGISTRY_PORT=5001
export SIMPLEMATCH_CERTIFICATION_CACHE_DIR="$fixture_root/cache"

encode() {
  printf '%s' "$1" | base64 | tr -d '\n'
}

write_lock() {
  local lock_file="$1"
  local image_class service build_source repository digest

  : >"$lock_file"
  while IFS='|' read -r _ service _ repository; do
    digest="$(printf '%s' "$service" | sha256sum | awk '{print $1}')"
    printf '%s|%s|%s|%s\n' \
      "$service" "$repository:focused" \
      "localhost:5001/$repository:focused" \
      "localhost:5001/$repository@sha256:$digest" >>"$lock_file"
  done < <(simplematch_local_image_inventory_entries)
}

write_workload_json() {
  local lock_file="$1"
  local output_file="$2"
  local service reference workload_name items='[]'

  while IFS='|' read -r service _ _ reference; do
    workload_name="$service"
    [[ "$service" == flyway-runner ]] && workload_name=account-service-flyway
    items="$(jq --arg name "$workload_name" --arg image "$reference" \
      '. + [{kind:"Deployment", metadata:{name:$name},
        spec:{template:{spec:{containers:[{name:$name,image:$image}]}}}}]' \
      <<<"$items")"
  done <"$lock_file"
  items="$(jq '. + [{kind:"Deployment",metadata:{name:"kafka-connect"},
    spec:{template:{spec:{containers:[{name:"kafka-connect",
      image:"quay.io/debezium/connect:3.6.0.Final"}]}}}}]' <<<"$items")"
  jq -n --argjson items "$items" '{apiVersion:"v1",kind:"List",items:$items}' >"$output_file"
}

write_secrets_json() {
  local output_file="$1"
  local dsn='postgresql://simplematch:simplematch@postgres:5432/simplematch'
  local secrets='[]' service

  secrets="$(jq --arg dsn "$(encode "$dsn")" \
    '. + [{metadata:{name:"simplematch-flyway-secrets"},data:{postgres_dsn:$dsn}}]' \
    <<<"$secrets")"
  secrets="$(jq --arg user "$(encode simplematch)" \
    --arg password "$(encode simplematch)" \
    '. + [{metadata:{name:"simplematch-postgres-secrets"},
      data:{postgres_user:$user,postgres_password:$password}}]' <<<"$secrets")"
  for service in \
      account-service risk-service persistence market-data-projection \
      query-service quickfix-gateway; do
    secrets="$(jq --arg name "${service}-secrets" \
      --arg dsn "$(encode "$dsn")" \
      '. + [{metadata:{name:$name},data:{postgres_dsn:$dsn}}]' <<<"$secrets")"
  done
  jq -n --argjson items "$secrets" '{apiVersion:"v1",kind:"SecretList",items:$items}' >"$output_file"
}

write_fake_kubectl() {
  local fake_bin="$1"
  cat >"$fake_bin" <<'EOF_KUBECTL'
#!/usr/bin/env bash
set -Eeuo pipefail
root="${FAKE_KUBECTL_ROOT:?FAKE_KUBECTL_ROOT is required}"
case "$*" in
  *'config current-context'*) cat "$root/current-context" ;;
  *'get namespace'*) cat "$root/namespace.json" ;;
  *'get deployments,statefulsets,jobs'*) cat "$root/workloads.json" ;;
  *'get configmap matching-session-config'*) cat "$root/matching-session-config.json" ;;
  *'get configmap matching-daily-artifact'*) cat "$root/matching-daily-artifact.json" ;;
  *'get configmap quickfix-gateway-fix-spec'*) cat "$root/quickfix-gateway-fix-spec.json" ;;
  *'get configmap risk-service-config'*) cat "$root/risk-service-config.json" ;;
  *'get secrets'*) cat "$root/secrets.json" ;;
  *)
    printf 'unexpected fake kubectl invocation: %s\n' "$*" >&2
    exit 1
    ;;
esac
EOF_KUBECTL
  chmod 755 "$fake_bin"
}

write_fake_observer() {
  local fake_bin="$1"
  cat >"$fake_bin" <<'EOF_OBSERVER'
#!/usr/bin/env bash
set -Eeuo pipefail
output=''
while (($# > 0)); do
  case "$1" in
    --evidence-dir) output="$2"; shift 2 ;;
    *) shift ;;
  esac
done
printf '%s\n' invoked >"${FAKE_OBSERVER_MARKER:?FAKE_OBSERVER_MARKER is required}"
mkdir -p "$output"
jq -n '{status:"PASS"}' >"$output/verdict.json"
EOF_OBSERVER
  chmod 755 "$fake_bin"
}

write_fake_verifier_contract() {
  local fake_bin="$1"
  printf '%s\n' 'printf "%s\\n" helper-loaded' >"${fake_bin}.helper"
  cat >"$fake_bin" <<'EOF_CONTRACT'
#!/usr/bin/env bash
set -Eeuo pipefail
helper="${BASH_SOURCE[0]}.helper"
[[ -f "$helper" ]] || exit 1
source "$helper"
printf '%s\n' invoked >"${FAKE_VERIFIER_CONTRACT_MARKER:?FAKE_VERIFIER_CONTRACT_MARKER is required}"
printf '%s\n' 'CDC observer fixture header contract is valid.'
EOF_CONTRACT
  chmod 755 "$fake_bin"
}

write_dependencies() {
  local evidence_dir="$1"
  local dependency image_lock_digest result_file input_fingerprint definition_version
  local lock_content_base64 source_revision plan_phases='[]' evidence_digest
  local phase_policy plan_decision result_decision target_policy

  image_transport=registry
  image_tag=focused
  skip_build=false
  skip_compose=false
  skip_kubernetes=false
  matching_fleet_only=false
  certification_phase_graph_initialize || return 1
  SIMPLEMATCH_FOCUSED_DEPENDENCIES=()
  declare -gA SIMPLEMATCH_FOCUSED_DEPENDENCY_SEEN=()
  simplematch_focused_collect_dependencies kubernetes-cdc-delivery || return 1
  source_revision="$(git -C "$repo_root" rev-parse HEAD)"
  for dependency in "${SIMPLEMATCH_FOCUSED_DEPENDENCIES[@]}"; do
    mkdir -p "$evidence_dir/phases/$dependency"
    input_fingerprint="sha256:$(printf '%s' "$dependency" | sha256sum | awk '{print $1}')"
    definition_version="$(certification_phase_definition_version "$dependency")"
    result_file="$evidence_dir/phases/$dependency/result.json"
    if [[ "$dependency" == registry-image-lock ]]; then
      image_lock_digest="$(sha256sum "$evidence_dir/local-images.lock" | awk '{print $1}')"
      lock_content_base64="$(base64 <"$evidence_dir/local-images.lock" | tr -d '\n')"
      jq -n --arg phase "$dependency" --arg identity "sha256:$image_lock_digest" \
        --arg input "$input_fingerprint" --argjson definition "$definition_version" \
        --arg content "$lock_content_base64" \
        --arg source "$source_revision" \
        '{schemaVersion:1,phaseId:$phase,definitionVersion:$definition,
          decision:"EXECUTED",reason:"focused fixture",planning:{lookupDurationMillis:0,revalidationDurationMillis:0},
          execution:{startedAtUtc:"2026-09-03T00:00:00Z",completedAtUtc:"2026-09-03T00:00:01Z",durationMillis:1000,sourceRevision:$source},
          inputFingerprint:$input,status:"PASS",
          outputs:[{kind:"image-lock",name:"local-images",identity:$identity,
            contentBase64:$content}]}' \
        >"$result_file"
    else
      jq -n --arg phase "$dependency" --arg input "$input_fingerprint" \
        --argjson definition "$definition_version" --arg source "$source_revision" \
        '{schemaVersion:1,phaseId:$phase,definitionVersion:$definition,
          decision:"EXECUTED",reason:"focused fixture",planning:{lookupDurationMillis:0,revalidationDurationMillis:0},
          execution:{startedAtUtc:"2026-09-03T00:00:00Z",completedAtUtc:"2026-09-03T00:00:01Z",durationMillis:1000,sourceRevision:$source},
          inputFingerprint:$input,status:"PASS",outputs:[]}' \
        >"$result_file"
    fi
    phase_policy="$(certification_phase_policy "$dependency")"
    plan_decision=EXECUTE
    result_decision=EXECUTED
    case "$phase_policy" in
      CONTENT_ADDRESSED)
        result_decision=REUSED
        ;;
      REVALIDATE)
        result_decision=REVALIDATED
        ;;
      FRESH) ;;
      *) return 1 ;;
    esac
    plan_decision="$(simplematch_focused_plan_decision_for_result \
      "$result_decision")" || return 1
    if [[ "$phase_policy" != FRESH ]]; then
      evidence_digest="$(certification_evidence_publish \
        "$dependency" "$input_fingerprint" "$result_file")" || return 1
      jq --arg evidence "$evidence_digest" --arg decision "$result_decision" \
        '.evidenceDigest = $evidence | .decision = $decision' \
        "$result_file" >"$result_file.tmp" || return 1
      mv -f -- "$result_file.tmp" "$result_file"
    fi
    evidence_digest="$(jq -r '.evidenceDigest // ""' "$result_file")"
    plan_phases="$(jq --arg phase "$dependency" --arg input "$input_fingerprint" \
      --arg policy "$phase_policy" \
      --arg decision "$plan_decision" \
      --arg evidence "$evidence_digest" \
      '. + [{phaseId:$phase,policy:$policy,
        decision:$decision,inputFingerprint:$input,
        lookupDurationMillis:0,revalidationDurationMillis:0,
        evidenceDigest:(if $evidence == "" then null else $evidence end)}]' <<<"$plan_phases")"
  done
  mkdir -p "$evidence_dir/phases/kubernetes-cdc-delivery"
  jq -n '{schemaVersion:1,phaseId:"kubernetes-cdc-delivery",status:"FAIL"}' \
    >"$evidence_dir/phases/kubernetes-cdc-delivery/result.json"
  target_policy="$(certification_phase_policy kubernetes-cdc-delivery)"
  plan_phases="$(jq --arg policy "$target_policy" \
    '. + [{phaseId:"kubernetes-cdc-delivery",policy:$policy,decision:"EXECUTE",
      inputFingerprint:null,lookupDurationMillis:0,revalidationDurationMillis:0,
      evidenceDigest:null}]' <<<"$plan_phases")"
  jq -n --argjson phases "$plan_phases" '{schemaVersion:1,phases:$phases}' >"$evidence_dir/plan.json"
}

write_fixture() {
  local evidence_dir="$1"
  local source_signature="$2"
  local runtime_signature="${3:-$current_runtime_signature}"
  local verifier_signature="${4:-$current_verifier_signature}"
  local profile_override="${5:-}"
  local image_lock matching_digest

  mkdir -p "$evidence_dir"
  image_lock="$evidence_dir/local-images.lock"
  write_lock "$image_lock"
  matching_digest="$(simplematch_local_image_lock_digest "$image_lock" matching)"
  printf '%s\n' kind-simplematch-live >"$evidence_dir/current-context"
  jq -n '{metadata:{labels:{
    "simplematch.io/lifecycle":"disposable",
    "simplematch.io/managed-by":"local-production-like-certification",
    "simplematch.io/run-id":"20260903-123456-1"}}}' >"$evidence_dir/namespace.json"
  printf '%s\n' "run_id=20260903-123456-1" \
    "namespace=simplematch-local-cert-focused" \
    'cluster=simplematch-live' \
    'trading_day=2026-08-27' \
    'image_tag=focused' \
    'image_transport=registry' \
    "source_signature=$source_signature" \
    "cdc_runtime_signature=$runtime_signature" \
    "cdc_verifier_signature=$verifier_signature" \
    "skip_build=${profile_override:-false}" \
    'skip_compose=false' \
    'skip_kubernetes=false' \
    'matching_fleet_only=false' >"$evidence_dir/run-context"
  jq -n --arg digest "$matching_digest" '{immutable:true,data:{
    trading_day:"2026-08-27",trading_session_id:"2026-08-27-regular",
    matching_image_digest:$digest}}' >"$evidence_dir/matching-session-config.json"
  jq -n '{immutable:true,data:{artifact:"fixture"}}' >"$evidence_dir/matching-daily-artifact.json"
  jq -n '{immutable:true,data:{fix:"fixture"}}' >"$evidence_dir/quickfix-gateway-fix-spec.json"
  jq -n '{data:{"application.yaml":"simplematch:\n  maximum-metric-age: 60s\n"}}' \
    >"$evidence_dir/risk-service-config.json"
  write_secrets_json "$evidence_dir/secrets.json"
  write_workload_json "$image_lock" "$evidence_dir/workloads.json"
  write_dependencies "$evidence_dir"
  printf '%s\n' "$(git -C "$repo_root" rev-parse HEAD)" \
    >"$evidence_dir/source-revision"
}

fake_kubectl="$fixture_root/fake-kubectl"
fake_observer="$fixture_root/fake-observer"
fake_verifier_contract="$fixture_root/fake-verifier-contract"
write_fake_kubectl "$fake_kubectl"
write_fake_observer "$fake_observer"
write_fake_verifier_contract "$fake_verifier_contract"

source_signature="$(simplematch_certification_source_signature "$repo_root")"
current_runtime_signature="$(
  simplematch_certification_cdc_runtime_signature "$repo_root"
)"
current_verifier_signature="$(
  simplematch_certification_cdc_verifier_signature "$repo_root"
)"
fake_verifier_signature="$(
  simplematch_certification_cdc_verifier_signature \
    "$repo_root" "$fake_verifier_contract" "$fake_observer"
)"

run_expect_failure_without_observer() {
  local evidence_dir="$1"
  local marker="$fixture_root/observer-marker"
  rm -f -- "$marker"
  if FAKE_KUBECTL_ROOT="$evidence_dir" \
      FAKE_OBSERVER_MARKER="$marker" \
      FAKE_VERIFIER_CONTRACT_MARKER="$fixture_root/verifier-contract-marker" \
      SIMPLEMATCH_FOCUSED_KUBECTL_BIN="$fake_kubectl" \
      SIMPLEMATCH_CDC_OBSERVER_SCRIPT="$fake_observer" \
      SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT="$fake_verifier_contract" \
      "$runner" --evidence-dir "$evidence_dir" --timeout-seconds 31 \
      >/dev/null 2>&1; then
    fail "invalid focused diagnostic unexpectedly passed: $evidence_dir"
  fi
  [[ ! -e "$marker" ]] || fail \
    "observer was invoked before preflight rejected $evidence_dir"
}

replace_context_value() {
  local context_file="$1" key="$2" value="$3" line found=false
  local temporary_file="${context_file}.tmp"

  while IFS= read -r line; do
    if [[ "$line" == "$key="* ]]; then
      printf '%s=%s\n' "$key" "$value"
      found=true
    else
      printf '%s\n' "$line"
    fi
  done <"$context_file" >"$temporary_file"
  [[ "$found" == true ]] || {
    rm -f -- "$temporary_file"
    return 1
  }
  mv -f -- "$temporary_file" "$context_file"
}

# Build the expensive phase/evidence tree once; each case mutates one input.
base_fixture="$fixture_root/base"
write_fixture "$base_fixture" "$source_signature" \
  "$current_runtime_signature" "$fake_verifier_signature"

missing_context="$fixture_root/missing-context"
cp -R -- "$base_fixture" "$missing_context"
rm -f -- "$missing_context/current-context"
run_expect_failure_without_observer "$missing_context"

profile_fixture="$fixture_root/profile"
cp -R -- "$base_fixture" "$profile_fixture"
replace_context_value "$profile_fixture/run-context" skip_build true
run_expect_failure_without_observer "$profile_fixture"

dependency_fixture="$fixture_root/dependency"
cp -R -- "$base_fixture" "$dependency_fixture"
dependency_result="$dependency_fixture/phases/kubernetes-workloads/result.json"
jq '.status = "FAIL"' "$dependency_result" >"$dependency_result.tmp"
mv -f -- "$dependency_result.tmp" "$dependency_result"
run_expect_failure_without_observer "$dependency_fixture"

runtime_fixture="$fixture_root/runtime-drift"
cp -R -- "$base_fixture" "$runtime_fixture"
replace_context_value "$runtime_fixture/run-context" cdc_runtime_signature \
  0000000000000000000000000000000000000000000000000000000000000000
run_expect_failure_without_observer "$runtime_fixture"

swapped_image_fixture="$fixture_root/swapped-image"
cp -R -- "$base_fixture" "$swapped_image_fixture"
account_image="$(jq -r '.items[] | select(.metadata.name == "account-service") |
  .spec.template.spec.containers[0].image' "$swapped_image_fixture/workloads.json")"
risk_image="$(jq -r '.items[] | select(.metadata.name == "risk-service") |
  .spec.template.spec.containers[0].image' "$swapped_image_fixture/workloads.json")"
jq --arg account "$account_image" --arg risk "$risk_image" '
  (.items[] | select(.metadata.name == "account-service") |
    .spec.template.spec.containers[0].image) = $risk |
  (.items[] | select(.metadata.name == "risk-service") |
    .spec.template.spec.containers[0].image) = $account
' "$swapped_image_fixture/workloads.json" \
  >"$swapped_image_fixture/workloads.json.tmp"
mv -f -- "$swapped_image_fixture/workloads.json.tmp" \
  "$swapped_image_fixture/workloads.json"
run_expect_failure_without_observer "$swapped_image_fixture"

image_lock_fixture="$fixture_root/image-lock-drift"
cp -R -- "$base_fixture" "$image_lock_fixture"
sed '1s/:focused/:tampered/' "$image_lock_fixture/local-images.lock" \
  >"$image_lock_fixture/local-images.lock.tmp"
mv -f -- "$image_lock_fixture/local-images.lock.tmp" \
  "$image_lock_fixture/local-images.lock"
run_expect_failure_without_observer "$image_lock_fixture"

unrelated_fixture="$fixture_root/unrelated-source-drift"
cp -R -- "$base_fixture" "$unrelated_fixture"
replace_context_value "$unrelated_fixture/run-context" source_signature \
  0000000000000000000000000000000000000000000000000000000000000000
unrelated_marker="$fixture_root/unrelated-observer-marker"
if ! FAKE_KUBECTL_ROOT="$unrelated_fixture" \
    FAKE_OBSERVER_MARKER="$unrelated_marker" \
    FAKE_VERIFIER_CONTRACT_MARKER="$fixture_root/unrelated-verifier-contract-marker" \
    SIMPLEMATCH_FOCUSED_KUBECTL_BIN="$fake_kubectl" \
    SIMPLEMATCH_CDC_OBSERVER_SCRIPT="$fake_observer" \
    SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT="$fake_verifier_contract" \
    "$runner" --evidence-dir "$unrelated_fixture" --timeout-seconds 31 \
    >/dev/null; then
  fail 'unrelated source drift incorrectly invalidated the CDC diagnostic'
fi
[[ -f "$unrelated_marker" ]] || fail \
  'unrelated source drift did not reach the observer'

verifier_fixture="$fixture_root/verifier-drift"
cp -R -- "$base_fixture" "$verifier_fixture"
replace_context_value "$verifier_fixture/run-context" cdc_verifier_signature \
  0000000000000000000000000000000000000000000000000000000000000000
verifier_marker="$fixture_root/verifier-observer-marker"
if ! FAKE_KUBECTL_ROOT="$verifier_fixture" \
    FAKE_OBSERVER_MARKER="$verifier_marker" \
    FAKE_VERIFIER_CONTRACT_MARKER="$fixture_root/verifier-contract-marker" \
    SIMPLEMATCH_FOCUSED_KUBECTL_BIN="$fake_kubectl" \
    SIMPLEMATCH_CDC_OBSERVER_SCRIPT="$fake_observer" \
    SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT="$fake_verifier_contract" \
    "$runner" --evidence-dir "$verifier_fixture" --timeout-seconds 31 \
    >/dev/null; then
  fail 'verifier-only drift incorrectly required a full certification run'
fi
[[ -f "$verifier_marker" ]] || fail \
  'verifier-only drift did not reach the observer'

valid_fixture="$fixture_root/valid"
cp -R -- "$base_fixture" "$valid_fixture"
marker="$fixture_root/observer-marker"
if ! FAKE_KUBECTL_ROOT="$valid_fixture" \
    FAKE_OBSERVER_MARKER="$marker" \
    FAKE_VERIFIER_CONTRACT_MARKER="$fixture_root/valid-verifier-contract-marker" \
    SIMPLEMATCH_FOCUSED_KUBECTL_BIN="$fake_kubectl" \
    SIMPLEMATCH_CDC_OBSERVER_SCRIPT="$fake_observer" \
    SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT="$fake_verifier_contract" \
    "$runner" --evidence-dir "$valid_fixture" --timeout-seconds 31 \
    >/dev/null; then
  fail 'valid retained run did not reach the observer'
fi
[[ -f "$marker" ]] || fail 'valid retained run did not invoke the observer'
[[ -f "$fixture_root/valid-verifier-contract-marker" ]] || fail \
  'valid retained run did not invoke the verifier contract'
top_level_verdict="$(find "$valid_fixture/focused-diagnostics/cdc-delivery" \
  -mindepth 2 -maxdepth 2 -name verdict.json ! -path '*/observer/*' -print -quit)"
[[ -f "$top_level_verdict" ]] || fail 'focused diagnostic verdict was not materialized'
jq -e '.status == "PASS" and .mode == "FOCUSED_DIAGNOSTIC" and
  .schemaVersion == 2 and
  .fullCertification == false and .targetPhase == "kubernetes-cdc-delivery" and
  .verifierChanged == false and
  .runtimeReused == true and
  (.currentCdcRuntimeSignature | test("^[0-9a-f]{64}$")) and
  (.cdcRuntimeSignature | test("^[0-9a-f]{64}$")) and
  (.verifierObserverSha256 | test("^[0-9a-f]{64}$")) and
  .verifierObserverEvidenceFile == "verifier-observer.sh" and
  (.verifierContractSha256 | test("^[0-9a-f]{64}$")) and
  .verifierContractEvidenceFile == "verifier-contract.sh" and
  (.verifierContractPath | endswith("/fake-verifier-contract"))' \
  "$top_level_verdict" >/dev/null || fail 'focused diagnostic verdict is not diagnostic-only PASS'
contract_copy="$(dirname -- "$top_level_verdict")/verifier-contract.sh"
[[ -f "$contract_copy" && ! -L "$contract_copy" ]] || fail \
  'focused diagnostic did not retain a non-symlink verifier contract copy'
cmp -s "$fake_verifier_contract" "$contract_copy" || fail \
  'focused diagnostic verifier contract copy differs from the executed contract'
observer_copy="$(dirname -- "$top_level_verdict")/verifier-observer.sh"
[[ -f "$observer_copy" && ! -L "$observer_copy" ]] || fail \
  'focused diagnostic did not retain a non-symlink observer copy'
cmp -s "$fake_observer" "$observer_copy" || fail \
  'focused diagnostic observer copy differs from the executed observer'
preflight_file="$(dirname -- "$top_level_verdict")/preflight.json"
jq -e '.schemaVersion == 2 and
  .verifierObserverEvidenceFile == "verifier-observer.sh" and
  .verifierContractEvidenceFile == "verifier-contract.sh"' \
  "$preflight_file" >/dev/null || fail 'focused preflight schema/linkage is invalid'

plan_mismatch_fixture="$fixture_root/plan-mismatch"
cp -R -- "$base_fixture" "$plan_mismatch_fixture"
jq '(.phases[] | select(.phaseId == "static-matching-profile")).decision = "EXECUTE"' \
  "$plan_mismatch_fixture/plan.json" >"$plan_mismatch_fixture/plan.json.tmp"
mv -f -- "$plan_mismatch_fixture/plan.json.tmp" "$plan_mismatch_fixture/plan.json"
if FAKE_KUBECTL_ROOT="$plan_mismatch_fixture" \
    FAKE_OBSERVER_MARKER="$fixture_root/plan-mismatch-observer-marker" \
    FAKE_VERIFIER_CONTRACT_MARKER="$fixture_root/plan-mismatch-contract-marker" \
    SIMPLEMATCH_FOCUSED_KUBECTL_BIN="$fake_kubectl" \
    SIMPLEMATCH_CDC_OBSERVER_SCRIPT="$fake_observer" \
    SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT="$fake_verifier_contract" \
    "$runner" --evidence-dir "$plan_mismatch_fixture" --timeout-seconds 31 \
    >/dev/null; then
  fail 'plan/result decision mismatch was accepted by focused preflight'
fi

revalidate_mismatch_fixture="$fixture_root/revalidate-mismatch"
cp -R -- "$base_fixture" "$revalidate_mismatch_fixture"
jq '(.phases[] | select(.phaseId == "registry-publish/account-service")).decision = "REUSE"' \
  "$revalidate_mismatch_fixture/plan.json" >"$revalidate_mismatch_fixture/plan.json.tmp"
mv -f -- "$revalidate_mismatch_fixture/plan.json.tmp" "$revalidate_mismatch_fixture/plan.json"
jq '.decision = "REUSED"' \
  "$revalidate_mismatch_fixture/phases/registry-publish/account-service/result.json" \
  >"$revalidate_mismatch_fixture/phases/registry-publish/account-service/result.json.tmp"
mv -f -- \
  "$revalidate_mismatch_fixture/phases/registry-publish/account-service/result.json.tmp" \
  "$revalidate_mismatch_fixture/phases/registry-publish/account-service/result.json"
if FAKE_KUBECTL_ROOT="$revalidate_mismatch_fixture" \
    FAKE_OBSERVER_MARKER="$fixture_root/revalidate-mismatch-observer-marker" \
    FAKE_VERIFIER_CONTRACT_MARKER="$fixture_root/revalidate-mismatch-contract-marker" \
    SIMPLEMATCH_FOCUSED_KUBECTL_BIN="$fake_kubectl" \
    SIMPLEMATCH_CDC_OBSERVER_SCRIPT="$fake_observer" \
    SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT="$fake_verifier_contract" \
    "$runner" --evidence-dir "$revalidate_mismatch_fixture" --timeout-seconds 31 \
    >/dev/null; then
  fail 'REVALIDATE dependency accepted a cached REUSED result'
fi

binding_result="$valid_fixture/phases/registry-image-lock/result.json"
binding_digest="$(jq -er '.evidenceDigest' "$binding_result")"
binding_object="$(_certification_evidence_object_path "$binding_digest")"
binding_valid_result="$fixture_root/binding-valid-result.json"
cp -- "$binding_result" "$binding_valid_result"
simplematch_focused_result_is_bound_to_object \
  "$binding_valid_result" "$binding_object" "$binding_digest" || fail \
  'reused phase metadata was rejected despite matching immutable evidence'
jq '.decision = "REVALIDATED" |
  .reason = "current external validation" |
  .planning.lookupDurationMillis = 999 |
  .planning.revalidationDurationMillis = 17 |
  .execution.startedAtUtc = "2026-09-06T00:00:00Z" |
  .execution.completedAtUtc = "2026-09-06T00:00:02Z" |
  .execution.durationMillis = 2000' "$binding_valid_result" \
  >"$binding_result.tmp"
mv -f -- "$binding_result.tmp" "$binding_result"
simplematch_focused_result_is_bound_to_object \
  "$binding_result" "$binding_object" "$binding_digest" || fail \
  'revalidated phase metadata was rejected despite matching immutable evidence'
cp -- "$binding_result" "$binding_valid_result"
for mutation in \
    '.schemaVersion = 2' \
    '.phaseId = "tampered-phase"' \
    '.definitionVersion = (.definitionVersion + 1)' \
    '.status = "FAIL"' \
    '.inputFingerprint = "sha256:0000000000000000000000000000000000000000000000000000000000000000"' \
    '.outputs = []'; do
  jq "$mutation" "$binding_valid_result" >"$binding_result.tmp"
  mv -f -- "$binding_result.tmp" "$binding_result"
  if simplematch_focused_result_is_bound_to_object \
      "$binding_result" "$binding_object" "$binding_digest"; then
    fail "immutable evidence drift was accepted: $mutation"
  fi
done
cp -- "$binding_valid_result" "$binding_result"
if simplematch_focused_result_is_bound_to_object \
    "$binding_result" "$binding_object" \
    sha256:0000000000000000000000000000000000000000000000000000000000000000; then
  fail 'wrong evidence digest was accepted by the binding seam'
fi
wrong_object="$fixture_root/wrong-evidence-object.json"
jq '.outputs = []' "$binding_object" >"$wrong_object"
if simplematch_focused_result_is_bound_to_object \
    "$binding_result" "$wrong_object" "$binding_digest"; then
  fail 'wrong-address evidence object was accepted by the binding seam'
fi

printf '%s\n' 'Local focused CDC diagnostic contracts are valid.'

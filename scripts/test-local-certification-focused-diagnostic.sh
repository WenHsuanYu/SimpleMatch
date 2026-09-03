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
# shellcheck source=scripts/lib/local-certification-focused-diagnostic.sh
source "$script_dir/lib/local-certification-focused-diagnostic.sh"

fail() {
  printf 'Focused CDC diagnostic contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-focused-cdc.XXXXXX")"
trap 'rm -rf -- "$fixture_root"' EXIT
export SIMPLEMATCH_LOCAL_REGISTRY_PORT=5001

encode() {
  printf '%s' "$1" | base64 | tr -d '\n'
}

write_lock() {
  local lock_file="$1"
  local image_class service build_source repository digest

  : >"$lock_file"
  while IFS='|' read -r image_class service build_source repository; do
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
  local service reference items='[]'

  while IFS='|' read -r service _ _ reference; do
    items="$(jq --arg name "$service" --arg image "$reference" \
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
      marketdata-publisher query-service quickfix-gateway; do
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

write_dependencies() {
  local evidence_dir="$1"
  local dependency

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
  for dependency in "${SIMPLEMATCH_FOCUSED_DEPENDENCIES[@]}"; do
    mkdir -p "$evidence_dir/phases/$dependency"
    jq -n --arg phase "$dependency" \
      '{schemaVersion:1,phaseId:$phase,status:"PASS"}' \
      >"$evidence_dir/phases/$dependency/result.json"
  done
  mkdir -p "$evidence_dir/phases/kubernetes-cdc-delivery"
  jq -n '{schemaVersion:1,phaseId:"kubernetes-cdc-delivery",status:"FAIL"}' \
    >"$evidence_dir/phases/kubernetes-cdc-delivery/result.json"
  jq -n --argjson phases "$(
    printf '%s\n' "${SIMPLEMATCH_FOCUSED_DEPENDENCIES[@]}" \
      kubernetes-cdc-delivery | jq -Rsc \
      'split("\n") | map(select(length > 0) | {phaseId:.,decision:"EXECUTE"})'
  )" '{schemaVersion:1,phases:$phases}' >"$evidence_dir/plan.json"
}

write_fixture() {
  local evidence_dir="$1"
  local source_signature="$2"
  local profile_override="${3:-}"
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
}

fake_kubectl="$fixture_root/fake-kubectl"
fake_observer="$fixture_root/fake-observer"
write_fake_kubectl "$fake_kubectl"
write_fake_observer "$fake_observer"

source_signature="$(simplematch_certification_source_signature "$repo_root")"

run_expect_failure_without_observer() {
  local evidence_dir="$1"
  local marker="$fixture_root/observer-marker"
  rm -f -- "$marker"
  if FAKE_KUBECTL_ROOT="$evidence_dir" \
      FAKE_OBSERVER_MARKER="$marker" \
      SIMPLEMATCH_FOCUSED_KUBECTL_BIN="$fake_kubectl" \
      SIMPLEMATCH_CDC_OBSERVER_SCRIPT="$fake_observer" \
      "$runner" --evidence-dir "$evidence_dir" --timeout-seconds 31 \
      >/dev/null 2>&1; then
    fail "invalid focused diagnostic unexpectedly passed: $evidence_dir"
  fi
  [[ ! -e "$marker" ]] || fail \
    "observer was invoked before preflight rejected $evidence_dir"
}

missing_context="$fixture_root/missing-context"
mkdir -p "$missing_context"
run_expect_failure_without_observer "$missing_context"

profile_fixture="$fixture_root/profile"
write_fixture "$profile_fixture" "$source_signature" true
run_expect_failure_without_observer "$profile_fixture"

dependency_fixture="$fixture_root/dependency"
write_fixture "$dependency_fixture" "$source_signature"
dependency_result="$dependency_fixture/phases/kubernetes-workloads/result.json"
jq '.status = "FAIL"' "$dependency_result" >"$dependency_result.tmp"
mv -f -- "$dependency_result.tmp" "$dependency_result"
run_expect_failure_without_observer "$dependency_fixture"

source_fixture="$fixture_root/source-drift"
write_fixture "$source_fixture" \
  0000000000000000000000000000000000000000000000000000000000000000
run_expect_failure_without_observer "$source_fixture"

valid_fixture="$fixture_root/valid"
write_fixture "$valid_fixture" "$source_signature"
marker="$fixture_root/observer-marker"
if ! FAKE_KUBECTL_ROOT="$valid_fixture" \
    FAKE_OBSERVER_MARKER="$marker" \
    SIMPLEMATCH_FOCUSED_KUBECTL_BIN="$fake_kubectl" \
    SIMPLEMATCH_CDC_OBSERVER_SCRIPT="$fake_observer" \
    "$runner" --evidence-dir "$valid_fixture" --timeout-seconds 31 \
    >/dev/null; then
  fail 'valid retained run did not reach the observer'
fi
[[ -f "$marker" ]] || fail 'valid retained run did not invoke the observer'
top_level_verdict="$(find "$valid_fixture/focused-diagnostics/cdc-delivery" \
  -mindepth 2 -maxdepth 2 -name verdict.json ! -path '*/observer/*' -print -quit)"
[[ -f "$top_level_verdict" ]] || fail 'focused diagnostic verdict was not materialized'
jq -e '.status == "PASS" and .mode == "FOCUSED_DIAGNOSTIC" and
  .fullCertification == false and .targetPhase == "kubernetes-cdc-delivery"' \
  "$top_level_verdict" >/dev/null || fail 'focused diagnostic verdict is not diagnostic-only PASS'

printf '%s\n' 'Local focused CDC diagnostic contracts are valid.'

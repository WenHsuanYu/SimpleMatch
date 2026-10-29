#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
runner="$script_dir/run-local-cdc-delivery-focused-diagnostic.sh"


fail() {
  printf 'Focused CDC diagnostic contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-focused-cdc.XXXXXX")"
trap 'rm -rf -- "$fixture_root"' EXIT
export SIMPLEMATCH_LOCAL_REGISTRY_PORT=5001

# shellcheck source=scripts/testdata/focused-diagnostic/fixture.sh
source "$script_dir/testdata/focused-diagnostic/fixture.sh"

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

run_preflight_expect_failure() {
  local label="$1"
  local evidence_dir="$2"
  local output_dir="$fixture_root/preflight/$label"
  local marker="$fixture_root/preflight/$label-observer-marker"
  local contract_marker="$fixture_root/preflight/$label-contract-marker"
  local deadline_epoch
  local -A config

  deadline_epoch="$(( $(date +%s) + 60 ))"
  mkdir -p "$output_dir"
  config=(
    [evidence_dir]="$evidence_dir"
    [repo_root]="$repo_root"
    [kubectl_bin]="$fake_kubectl"
    [preflight_deadline_epoch]="$deadline_epoch"
    [image_lock]="$evidence_dir/local-images.lock"
    [observer_script]="$fake_observer"
    [verifier_observer_copy]="$output_dir/verifier-observer.sh"
    [verifier_contract_script]="$fake_verifier_contract"
    [verifier_contract_output]="$output_dir/verifier-contract.log"
    [verifier_contract_copy]="$output_dir/verifier-contract.sh"
  )
  rm -f -- "$marker" "$contract_marker"
  if (
    SIMPLEMATCH_FOCUSED_CONTEXT=()
    SIMPLEMATCH_FOCUSED_DEPENDENCIES=()
    SIMPLEMATCH_FOCUSED_FAILURE_REASON=''
    SIMPLEMATCH_FOCUSED_KIND_CONTEXT=''
    SIMPLEMATCH_FOCUSED_SOURCE_SIGNATURE=''
    SIMPLEMATCH_FOCUSED_RETAINED_CDC_RUNTIME_SIGNATURE=''
    SIMPLEMATCH_FOCUSED_RETAINED_CDC_VERIFIER_SIGNATURE=''
    SIMPLEMATCH_FOCUSED_CURRENT_CDC_RUNTIME_SIGNATURE=''
    SIMPLEMATCH_FOCUSED_CURRENT_CDC_VERIFIER_SIGNATURE=''
    SIMPLEMATCH_FOCUSED_VERIFIER_CHANGED=false
    SIMPLEMATCH_FOCUSED_CURRENT_REVISION=''
    SIMPLEMATCH_FOCUSED_IMAGE_LOCK_DIGEST=''
    SIMPLEMATCH_FOCUSED_VERIFIER_OBSERVER_PATH=''
    SIMPLEMATCH_FOCUSED_VERIFIER_OBSERVER_SHA256=''
    SIMPLEMATCH_FOCUSED_VERIFIER_OBSERVER_EVIDENCE_FILE=''
    SIMPLEMATCH_FOCUSED_VERIFIER_CONTRACT_PATH=''
    SIMPLEMATCH_FOCUSED_VERIFIER_CONTRACT_SHA256=''
    export FAKE_KUBECTL_ROOT="$evidence_dir"
    export FAKE_OBSERVER_MARKER="$marker"
    export FAKE_VERIFIER_CONTRACT_MARKER="$contract_marker"
    simplematch_focused_configure_inputs config
    simplematch_focused_preflight
  ) >/dev/null 2>&1; then
    fail "invalid focused preflight unexpectedly passed: $evidence_dir"
  fi
  [[ ! -e "$marker" ]] || fail \
    "observer was invoked during preflight for $evidence_dir"
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

security_fixture="$fixture_root/security-drift"
cp -R -- "$base_fixture" "$security_fixture"
jq '(.items[] | select(.metadata.name == "query-service-secrets") |
  .data["query-service.properties"]) = "Y2hhbmdlZC1wb2xpY3k="' \
  "$security_fixture/secrets.json" >"$security_fixture/secrets.json.tmp"
mv -- "$security_fixture/secrets.json.tmp" "$security_fixture/secrets.json"
run_preflight_expect_failure security-drift "$security_fixture"

security_identity_fixture="$fixture_root/missing-security-identity"
cp -R -- "$base_fixture" "$security_identity_fixture"
sed -i '/^query_security_deployed_digest=/d' "$security_identity_fixture/run-context"
run_preflight_expect_failure missing-security-identity "$security_identity_fixture"

missing_context="$fixture_root/missing-context"
cp -R -- "$base_fixture" "$missing_context"
rm -f -- "$missing_context/current-context"
run_expect_failure_without_observer "$missing_context"

profile_fixture="$fixture_root/profile"
cp -R -- "$base_fixture" "$profile_fixture"
replace_context_value "$profile_fixture/run-context" skip_build true
run_preflight_expect_failure profile "$profile_fixture"

dependency_fixture="$fixture_root/dependency"
cp -R -- "$base_fixture" "$dependency_fixture"
dependency_result="$dependency_fixture/phases/kubernetes-workloads/result.json"
jq '.status = "FAIL"' "$dependency_result" >"$dependency_result.tmp"
mv -f -- "$dependency_result.tmp" "$dependency_result"
run_preflight_expect_failure dependency "$dependency_fixture"

runtime_fixture="$fixture_root/runtime-drift"
cp -R -- "$base_fixture" "$runtime_fixture"
replace_context_value "$runtime_fixture/run-context" cdc_runtime_signature \
  0000000000000000000000000000000000000000000000000000000000000000
run_preflight_expect_failure runtime-drift "$runtime_fixture"

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
run_preflight_expect_failure swapped-image "$swapped_image_fixture"

image_lock_fixture="$fixture_root/image-lock-drift"
cp -R -- "$base_fixture" "$image_lock_fixture"
sed '1s/:focused/:tampered/' "$image_lock_fixture/local-images.lock" \
  >"$image_lock_fixture/local-images.lock.tmp"
mv -f -- "$image_lock_fixture/local-images.lock.tmp" \
  "$image_lock_fixture/local-images.lock"
run_preflight_expect_failure image-lock-drift "$image_lock_fixture"

unrelated_fixture="$fixture_root/unrelated-source-drift"
write_fixture "$unrelated_fixture" \
  0000000000000000000000000000000000000000000000000000000000000000 \
  "$current_runtime_signature" "$fake_verifier_signature"
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

wrong_run_fixture="$fixture_root/wrong-run"
cp -R -- "$base_fixture" "$wrong_run_fixture"
wrong_run_result="$wrong_run_fixture/phases/kubernetes-workloads/result.json"
jq '.runId = "another-run"' "$wrong_run_result" >"$wrong_run_result.tmp"
mv -f -- "$wrong_run_result.tmp" "$wrong_run_result"
run_preflight_expect_failure wrong-run "$wrong_run_fixture"

historical_fixture="$fixture_root/historical-result"
cp -R -- "$base_fixture" "$historical_fixture"
historical_result="$historical_fixture/phases/kubernetes-workloads/result.json"
jq '.schemaVersion = 1' "$historical_result" >"$historical_result.tmp"
mv -f -- "$historical_result.tmp" "$historical_result"
run_preflight_expect_failure historical-result "$historical_fixture"

printf '%s\n' 'Local focused CDC diagnostic contracts are valid.'

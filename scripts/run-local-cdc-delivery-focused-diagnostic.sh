#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

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

focused_evidence_dir=""
focused_output_dir=""
focused_repo_root="$repo_root"
focused_kubectl_bin="${SIMPLEMATCH_FOCUSED_KUBECTL_BIN:-kubectl}"
focused_timeout_seconds="${SIMPLEMATCH_CDC_OBSERVER_TIMEOUT_SECONDS:-180}"
focused_diagnostic_schema_version=2
focused_preflight_deadline_epoch=0
focused_image_transport=""
focused_image_lock=""
focused_observer_script="${SIMPLEMATCH_CDC_OBSERVER_SCRIPT:-$script_dir/run-risk-cdc-delivery-observer-check.sh}"
focused_verifier_contract_script="${SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT:-$script_dir/test-cdc-observer-fixture-contract.sh}"
focused_verifier_contract_output=""
focused_observer_status=""
focused_runtime_reused=false

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/run-local-cdc-delivery-focused-diagnostic.sh \
    --evidence-dir PATH [--timeout-seconds N]

Validate one retained full production-like run, then run only the Risk CDC
delivery observer. The run-context is the sole source of namespace, run-id,
cluster, image, and proof-profile identity. The retained CDC runtime signature
must match the current CDC deployment inputs; verifier-only changes are checked
by the fast observer contract before the command writes a diagnostic-only
verdict below the retained evidence directory. It never edits the full
certification plan or phase result.
EOF_USAGE
}

die() {
  printf 'Focused CDC diagnostic: %s\n' "$*" >&2
  exit 1
}

write_verdict() {
  local status="$1"
  local reason="$2"
  local observer_status_json=null
  local dependencies_json='[]'
  local namespace='' run_id='' source_signature='' runtime_signature=''
  local current_runtime_signature='' verifier_signature='' current_verifier_signature=''
  local runtime_reused='null' verifier_changed='null'
  local verifier_observer_path='' verifier_observer_sha256=''
  local verifier_observer_evidence_file=''
  local verifier_contract_path='' verifier_contract_sha256=''
  local verifier_contract_evidence_file=''

  if [[ -n "${focused_observer_status:-}" &&
    "$focused_observer_status" =~ ^[0-9]+$ ]]; then
    observer_status_json="$focused_observer_status"
  fi
  if ((${#SIMPLEMATCH_FOCUSED_DEPENDENCIES[@]} > 0)); then
    dependencies_json="$(printf '%s\n' \
      "${SIMPLEMATCH_FOCUSED_DEPENDENCIES[@]}" | jq -Rsc \
      'split("\n") | map(select(length > 0))')" || dependencies_json='[]'
  fi
  namespace="${SIMPLEMATCH_FOCUSED_CONTEXT[namespace]:-}"
  run_id="${SIMPLEMATCH_FOCUSED_CONTEXT[run_id]:-}"
  source_signature="$(simplematch_focused_source_signature)"
  runtime_signature="${SIMPLEMATCH_FOCUSED_CONTEXT[cdc_runtime_signature]:-}"
  current_runtime_signature="$(simplematch_focused_current_cdc_runtime_signature)"
  if [[ "$current_runtime_signature" =~ ^[0-9a-f]{64}$ ]]; then
    runtime_reused="$([[ "$current_runtime_signature" == "$runtime_signature" ]] &&
      printf true || printf false)"
  fi
  verifier_signature="${SIMPLEMATCH_FOCUSED_CONTEXT[cdc_verifier_signature]:-}"
  current_verifier_signature="$(simplematch_focused_current_cdc_verifier_signature)"
  if [[ "$current_verifier_signature" =~ ^[0-9a-f]{64}$ ]]; then
    verifier_changed="$([[ "$(simplematch_focused_verifier_changed)" == true ]] &&
      printf true || printf false)"
  fi
  verifier_observer_path="$(simplematch_focused_verifier_observer_path)"
  verifier_observer_sha256="$(simplematch_focused_verifier_observer_sha256)"
  verifier_observer_evidence_file="$(simplematch_focused_verifier_observer_evidence_file)"
  verifier_contract_path="$(simplematch_focused_verifier_contract_path)"
  verifier_contract_sha256="$(simplematch_focused_verifier_contract_sha256)"
  if [[ -f "$focused_verifier_contract_copy" && ! -L "$focused_verifier_contract_copy" ]]; then
    verifier_contract_evidence_file='verifier-contract.sh'
  fi
  jq -n \
    --arg status "$status" \
    --arg mode FOCUSED_DIAGNOSTIC \
    --arg targetPhase kubernetes-cdc-delivery \
    --arg namespace "$namespace" \
    --arg runId "$run_id" \
    --arg sourceSignature "$source_signature" \
    --arg cdcRuntimeSignature "$runtime_signature" \
    --arg currentCdcRuntimeSignature "$current_runtime_signature" \
    --argjson runtimeReused "$runtime_reused" \
    --arg cdcVerifierSignature "$verifier_signature" \
    --arg currentCdcVerifierSignature "$current_verifier_signature" \
    --argjson verifierChanged "$verifier_changed" \
    --arg verifierObserverPath "$verifier_observer_path" \
    --arg verifierObserverSha256 "$verifier_observer_sha256" \
    --arg verifierObserverEvidenceFile "$verifier_observer_evidence_file" \
    --arg verifierContractPath "$verifier_contract_path" \
    --arg verifierContractSha256 "$verifier_contract_sha256" \
    --arg verifierContractEvidenceFile "$verifier_contract_evidence_file" \
    --arg reason "$reason" \
    --arg createdAtUtc "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" \
    --argjson dependencies "$dependencies_json" \
    --argjson observerStatus "$observer_status_json" \
    --arg observerVerdict "${focused_output_dir:+$focused_output_dir/observer/verdict.json}" \
    --arg imageLockDigest "${SIMPLEMATCH_FOCUSED_IMAGE_LOCK_DIGEST:-}" \
    --argjson schemaVersion "$focused_diagnostic_schema_version" \
    '{schemaVersion: $schemaVersion, mode: $mode, status: $status,
      fullCertification: false, targetPhase: $targetPhase,
      namespace: $namespace, runId: $runId,
      sourceSignature: $sourceSignature,
      cdcRuntimeSignature: $cdcRuntimeSignature,
      currentCdcRuntimeSignature: $currentCdcRuntimeSignature,
      runtimeReused: $runtimeReused,
      retainedCdcVerifierSignature: $cdcVerifierSignature,
      currentCdcVerifierSignature: $currentCdcVerifierSignature,
      verifierChanged: $verifierChanged,
      verifierObserverPath:
        (if $verifierObserverPath == "" then null else $verifierObserverPath end),
      verifierObserverSha256:
        (if $verifierObserverSha256 == "" then null else $verifierObserverSha256 end),
      verifierObserverEvidenceFile:
        (if $verifierObserverEvidenceFile == "" then null else $verifierObserverEvidenceFile end),
      verifierContractPath:
        (if $verifierContractPath == "" then null else $verifierContractPath end),
      verifierContractSha256:
        (if $verifierContractSha256 == "" then null else $verifierContractSha256 end),
      verifierContractEvidenceFile:
        (if $verifierContractEvidenceFile == "" then null else $verifierContractEvidenceFile end),
      dependencies: $dependencies, imageLockDigest:
        (if $imageLockDigest == "" then null else ("sha256:" + $imageLockDigest) end),
      observerStatus: $observerStatus,
      observerVerdict: (if $observerVerdict == "/observer/verdict.json" then null else $observerVerdict end),
      reason: $reason, createdAtUtc: $createdAtUtc}' \
    >"$focused_output_dir/verdict.json"
}

while (($# > 0)); do
  case "$1" in
    --evidence-dir)
      focused_evidence_dir="${2:?--evidence-dir requires a value}"
      shift 2
      ;;
    --timeout-seconds)
      focused_timeout_seconds="${2:?--timeout-seconds requires a value}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown option: $1"
      ;;
  esac
done

[[ -n "$focused_evidence_dir" ]] || {
  usage >&2
  die '--evidence-dir is required'
}
[[ -d "$focused_evidence_dir" ]] || die \
  "retained evidence directory does not exist: $focused_evidence_dir"
[[ "$focused_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die \
  '--timeout-seconds must be a positive integer'
(( focused_timeout_seconds > 30 && focused_timeout_seconds <= 600 )) || die \
  '--timeout-seconds must be between 31 and 600'
command -v jq >/dev/null 2>&1 || die 'jq is required'
command -v timeout >/dev/null 2>&1 || die 'timeout is required'
command -v date >/dev/null 2>&1 || die 'date is required'
command -v sha256sum >/dev/null 2>&1 || die 'sha256sum is required'
command -v base64 >/dev/null 2>&1 || die 'base64 is required'
command -v sed >/dev/null 2>&1 || die 'sed is required'
command -v grep >/dev/null 2>&1 || die 'grep is required'
command -v cp >/dev/null 2>&1 || die 'cp is required'
command -v mkdir >/dev/null 2>&1 || die 'mkdir is required'
command -v awk >/dev/null 2>&1 || die 'awk is required'
command -v "$focused_kubectl_bin" >/dev/null 2>&1 || die \
  "kubectl executable is missing: $focused_kubectl_bin"
focused_observer_script="$(simplematch_certification_cdc_verifier_observer_path \
  "$repo_root" "$focused_observer_script")" || die \
  "CDC observer script is missing, symlinked, unreadable, or not executable: $focused_observer_script"
simplematch_certification_cdc_verifier_contract_path \
  "$repo_root" "$focused_verifier_contract_script" >/dev/null || die \
  "CDC verifier contract script is missing, symlinked, or not readable: $focused_verifier_contract_script"

focused_evidence_dir="$(cd -- "$focused_evidence_dir" && pwd)"
focused_image_lock="$focused_evidence_dir/local-images.lock"
focused_preflight_deadline_epoch=$(( $(date +%s) + 60 ))
focused_output_dir="$focused_evidence_dir/focused-diagnostics/cdc-delivery/$(date -u +%Y%m%d-%H%M%S)-$$"
mkdir -p "$focused_evidence_dir/focused-diagnostics/cdc-delivery"
mkdir "$focused_output_dir" || die \
  "focused diagnostic output directory already exists: $focused_output_dir"
mkdir "$focused_output_dir/observer" || die \
  'could not create the focused diagnostic observer directory'
focused_verifier_observer_copy="$focused_output_dir/verifier-observer.sh"
focused_verifier_contract_output="$focused_output_dir/verifier-contract.log"
focused_verifier_contract_copy="$focused_output_dir/verifier-contract.sh"
export SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT="$focused_verifier_contract_script"

if ! simplematch_focused_preflight; then
  failure_reason="$(simplematch_focused_failure_reason)"
  write_verdict FAIL "${failure_reason:-focused preflight failed}"
  exit 1
fi

if [[ "$(simplematch_focused_current_cdc_runtime_signature)" == "$SIMPLEMATCH_FOCUSED_RETAINED_CDC_RUNTIME_SIGNATURE" ]]; then
  focused_runtime_reused=true
fi

cat >"$focused_output_dir/preflight.json" <<EOF_PREFLIGHT
$(jq -n \
  --arg status PASS \
  --arg sourceRevision "$SIMPLEMATCH_FOCUSED_CURRENT_REVISION" \
  --arg sourceSignature "$(simplematch_focused_source_signature)" \
  --arg cdcRuntimeSignature \
    "$(simplematch_focused_current_cdc_runtime_signature)" \
  --arg retainedCdcRuntimeSignature \
    "$SIMPLEMATCH_FOCUSED_RETAINED_CDC_RUNTIME_SIGNATURE" \
  --argjson runtimeReused "$focused_runtime_reused" \
  --arg retainedCdcVerifierSignature \
    "$SIMPLEMATCH_FOCUSED_RETAINED_CDC_VERIFIER_SIGNATURE" \
  --arg currentCdcVerifierSignature \
    "$(simplematch_focused_current_cdc_verifier_signature)" \
  --argjson verifierChanged \
    "$([[ "$(simplematch_focused_verifier_changed)" == true ]] && printf true || printf false)" \
  --arg verifierObserverPath \
    "$(simplematch_focused_verifier_observer_path)" \
  --arg verifierObserverSha256 \
    "$(simplematch_focused_verifier_observer_sha256)" \
  --arg verifierObserverEvidenceFile \
    "$(simplematch_focused_verifier_observer_evidence_file)" \
  --arg verifierContractPath \
    "$(simplematch_focused_verifier_contract_path)" \
  --arg verifierContractSha256 \
    "$(simplematch_focused_verifier_contract_sha256)" \
  --arg verifierContractEvidenceFile verifier-contract.sh \
  --arg imageLockDigest "sha256:$SIMPLEMATCH_FOCUSED_IMAGE_LOCK_DIGEST" \
  --arg namespace "${SIMPLEMATCH_FOCUSED_CONTEXT[namespace]}" \
  --arg runId "${SIMPLEMATCH_FOCUSED_CONTEXT[run_id]}" \
  --arg context "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" \
  --argjson dependencies "$(printf '%s\n' \
    "${SIMPLEMATCH_FOCUSED_DEPENDENCIES[@]}" | jq -Rsc \
    'split("\n") | map(select(length > 0))')" \
  --argjson schemaVersion "$focused_diagnostic_schema_version" \
  '{schemaVersion: $schemaVersion, status: $status, namespace: $namespace,
    runId: $runId, kubernetesContext: $context,
    sourceRevision: $sourceRevision, sourceSignature: $sourceSignature,
    cdcRuntimeSignature: $cdcRuntimeSignature,
    retainedCdcRuntimeSignature: $retainedCdcRuntimeSignature,
    runtimeReused: $runtimeReused,
    retainedCdcVerifierSignature: $retainedCdcVerifierSignature,
    currentCdcVerifierSignature: $currentCdcVerifierSignature,
    verifierChanged: $verifierChanged,
    verifierObserverPath: $verifierObserverPath,
    verifierObserverSha256: $verifierObserverSha256,
    verifierObserverEvidenceFile: $verifierObserverEvidenceFile,
    verifierContractPath: $verifierContractPath,
    verifierContractSha256: $verifierContractSha256,
    verifierContractEvidenceFile: $verifierContractEvidenceFile,
    imageLockDigest: $imageLockDigest, dependencies: $dependencies}')
EOF_PREFLIGHT

set +e
"$focused_observer_script" \
  --namespace "${SIMPLEMATCH_FOCUSED_CONTEXT[namespace]}" \
  --namespace-run-id "${SIMPLEMATCH_FOCUSED_CONTEXT[run_id]}" \
  --evidence-dir "$focused_output_dir/observer" \
  --timeout-seconds "$focused_timeout_seconds" \
  >"$focused_output_dir/observer/stdout.log" \
  2>"$focused_output_dir/observer/stderr.log"
focused_observer_status=$?
set -e

if [[ "$focused_observer_status" -eq 0 &&
  -f "$focused_output_dir/observer/verdict.json" ]] &&
  jq -e '.status == "PASS"' \
    "$focused_output_dir/observer/verdict.json" >/dev/null 2>&1; then
  write_verdict PASS 'CDC observer passed after retained-run preflight'
  printf 'Focused CDC diagnostic passed: %s\n' "$focused_output_dir/verdict.json"
  exit 0
fi

observer_reason='CDC observer failed; inspect observer evidence'
if [[ -f "$focused_output_dir/observer/verdict.json" ]]; then
  observer_reason="$(jq -r '.reason // empty' \
    "$focused_output_dir/observer/verdict.json" 2>/dev/null || true)"
  [[ -n "$observer_reason" ]] || observer_reason='CDC observer returned an invalid verdict'
fi
write_verdict FAIL "$observer_reason"
printf 'Focused CDC diagnostic failed: %s\n' "$focused_output_dir/verdict.json" >&2
exit 1

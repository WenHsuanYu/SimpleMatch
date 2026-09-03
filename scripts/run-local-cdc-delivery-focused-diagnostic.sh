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
# shellcheck source=scripts/lib/local-certification-focused-diagnostic.sh
source "$script_dir/lib/local-certification-focused-diagnostic.sh"

focused_evidence_dir=""
focused_output_dir=""
focused_repo_root="$repo_root"
focused_kubectl_bin="${SIMPLEMATCH_FOCUSED_KUBECTL_BIN:-kubectl}"
focused_timeout_seconds="${SIMPLEMATCH_CDC_OBSERVER_TIMEOUT_SECONDS:-180}"
focused_preflight_deadline_epoch=0
focused_image_transport=""
focused_image_lock=""
focused_observer_script="${SIMPLEMATCH_CDC_OBSERVER_SCRIPT:-$script_dir/run-risk-cdc-delivery-observer-check.sh}"
focused_observer_status=""

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/run-local-cdc-delivery-focused-diagnostic.sh \
    --evidence-dir PATH [--timeout-seconds N]

Validate one retained full production-like run, then run only the Risk CDC
delivery observer. The run-context is the sole source of namespace, run-id,
cluster, source, image, and proof-profile identity. The command writes a
diagnostic-only verdict below the retained evidence directory and never edits
the full certification plan or phase result.
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
  local namespace='' run_id='' source_signature=''

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
  source_signature="${SIMPLEMATCH_FOCUSED_CONTEXT[source_signature]:-}"
  jq -n \
    --arg status "$status" \
    --arg mode FOCUSED_DIAGNOSTIC \
    --arg targetPhase kubernetes-cdc-delivery \
    --arg namespace "$namespace" \
    --arg runId "$run_id" \
    --arg sourceSignature "$source_signature" \
    --arg reason "$reason" \
    --arg createdAtUtc "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" \
    --argjson dependencies "$dependencies_json" \
    --argjson observerStatus "$observer_status_json" \
    --arg observerVerdict "${focused_output_dir:+$focused_output_dir/observer/verdict.json}" \
    --arg imageLockDigest "${SIMPLEMATCH_FOCUSED_IMAGE_LOCK_DIGEST:-}" \
    '{schemaVersion: 1, mode: $mode, status: $status,
      fullCertification: false, targetPhase: $targetPhase,
      namespace: $namespace, runId: $runId,
      sourceSignature: $sourceSignature,
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
command -v awk >/dev/null 2>&1 || die 'awk is required'
command -v "$focused_kubectl_bin" >/dev/null 2>&1 || die \
  "kubectl executable is missing: $focused_kubectl_bin"
[[ -x "$focused_observer_script" ]] || die \
  "CDC observer script is missing or not executable: $focused_observer_script"

focused_evidence_dir="$(cd -- "$focused_evidence_dir" && pwd)"
focused_image_lock="$focused_evidence_dir/local-images.lock"
focused_preflight_deadline_epoch=$(( $(date +%s) + 60 ))
focused_output_dir="$focused_evidence_dir/focused-diagnostics/cdc-delivery/$(date -u +%Y%m%d-%H%M%S)-$$"
mkdir -p "$focused_output_dir/observer"

if ! simplematch_focused_preflight; then
  failure_reason="$(simplematch_focused_failure_reason)"
  write_verdict FAIL "${failure_reason:-focused preflight failed}"
  exit 1
fi

cat >"$focused_output_dir/preflight.json" <<EOF_PREFLIGHT
$(jq -n \
  --arg status PASS \
  --arg sourceRevision "$SIMPLEMATCH_FOCUSED_CURRENT_REVISION" \
  --arg sourceSignature "$SIMPLEMATCH_FOCUSED_SOURCE_SIGNATURE" \
  --arg imageLockDigest "sha256:$SIMPLEMATCH_FOCUSED_IMAGE_LOCK_DIGEST" \
  --arg namespace "${SIMPLEMATCH_FOCUSED_CONTEXT[namespace]}" \
  --arg runId "${SIMPLEMATCH_FOCUSED_CONTEXT[run_id]}" \
  --arg context "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" \
  --argjson dependencies "$(printf '%s\n' \
    "${SIMPLEMATCH_FOCUSED_DEPENDENCIES[@]}" | jq -Rsc \
    'split("\n") | map(select(length > 0))')" \
  '{schemaVersion: 1, status: $status, namespace: $namespace,
    runId: $runId, kubernetesContext: $context,
    sourceRevision: $sourceRevision, sourceSignature: $sourceSignature,
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

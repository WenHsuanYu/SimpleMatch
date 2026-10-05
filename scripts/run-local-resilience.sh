#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

profile=""
dry_run=false
run_id="$(date -u +%Y%m%dt%H%M%sz)-$$"
evidence_dir="${SIMPLEMATCH_RESILIENCE_EVIDENCE_DIR:-$repo_root/out/resilience/$run_id}"

usage() {
  cat <<'EOF'
Usage:
  scripts/run-local-resilience.sh --profile contract [--dry-run]

The contract profile validates repository-rendered deployment contracts.
Runtime recovery is verified by property-specific focused diagnostics.
The former full-local workload-by-fault scenario matrix is retired.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) profile="${2:?--profile requires contract}"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

if [[ "$profile" != contract ]]; then
  printf '%s\n' 'Only --profile contract is supported; full-local is retired.' >&2
  usage >&2
  exit 2
fi

checks=(
  "$script_dir/test-simplematch-kind-manager.sh"
  "$script_dir/test-kubernetes-overlays.sh"
  "$script_dir/test-local-kubernetes-dependencies.sh"
  "$script_dir/test-postgresql-redis-manifests.sh"
  "$script_dir/test-kafka-kraft-manifests.sh"
  "$script_dir/test-local-resilience-dependencies.sh"
  "$script_dir/test-matching-kubernetes-manifests.sh"
  "$script_dir/validate-local-resilience-contract.sh"
)

if [[ "$dry_run" == true ]]; then
  printf 'DRY RUN: profile=contract checks=%s\n' "${#checks[@]}"
  printf '%s\n'     'DRY RUN: render topology, placement, PDB, resources, dependency, probe, and log contracts.'
  exit 0
fi

mkdir -p "$evidence_dir"
status=0
for check in "${checks[@]}"; do
  log_name="$(basename "$check" .sh).log"
  if ! bash "$check" >"$evidence_dir/$log_name" 2>&1; then
    status=1
  fi
done

if [[ "$status" -eq 0 ]]; then
  verdict=VALIDATED
else
  verdict=FAILED
fi

jq -n \
  --arg schema_version 1 \
  --arg run_id "$run_id" \
  --arg profile "$profile" \
  --arg verdict "$verdict" \
  '{
    schema_version: ($schema_version | tonumber),
    run_id: $run_id,
    profile: $profile,
    profile_verdict: $verdict,
    runtime_recovery_claim: "NOT_APPLICABLE",
    claim_boundary: "static deployment contract validation only"
  }' >"$evidence_dir/run-result.json"

exit "$status"

#!/usr/bin/env bash
set -euo pipefail

# These functions are called through the EXIT trap below.

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"
# shellcheck source=scripts/lib/local-resilience.sh
source "$script_dir/lib/local-resilience.sh"

profile=""
cluster_name=simplematch-live
context="kind-$cluster_name"
run_id="$(date -u +%Y%m%dt%H%M%sz)-$$"
evidence_dir="${SIMPLEMATCH_RESILIENCE_EVIDENCE_DIR:-$repo_root/out/resilience/$run_id}"
namespace="simplematch-resilience-$run_id"
keep_resources=false
dry_run=false
namespace_created=false
cleanup_result=NOT_EVALUATED
environment_safety_result=NOT_APPLICABLE
profile_verdict=INCOMPLETE
scenario_json='[]'
deadline_seconds="$RESILIENCE_DEFAULT_DEADLINE_SECONDS"
namespace_cleanup_timeout="${SIMPLEMATCH_NAMESPACE_CLEANUP_TIMEOUT_SECONDS:-180}"

usage() {
  cat <<'EOF'
Usage:
  scripts/run-local-resilience.sh --profile contract [--dry-run]
  scripts/run-local-resilience.sh --profile full-local [--keep-resources] [--dry-run]
  scripts/run-local-resilience-dependencies.sh --component COMPONENT --namespace NAME --namespace-run-id ID

contract validates repository-rendered targets without stopping a kind worker.
full-local owns one generated namespace and reports live scenario families;
unimplemented scenarios remain INCOMPLETE and cannot become a resilience pass.
The dependency command is a focused diagnostic for an existing disposable
namespace; its report cannot be promoted to a full-local certification pass.
EOF
}

# shellcheck disable=SC2317
write_result() {
  [[ "$dry_run" == true ]] && return 0
  mkdir -p "$evidence_dir/scenarios" "$evidence_dir/cleanup"
  local runtime_claim=NOT_PROVEN cleanup_reason=NORMAL_CLEANUP
  [[ "$profile" == contract ]] && runtime_claim=NOT_APPLICABLE
  [[ "$keep_resources" == true ]] && cleanup_reason=KEEP_RESOURCES_REQUESTED
  jq -n \
    --arg schema_version 1 --arg run_id "$run_id" --arg profile "$profile" \
    --arg cluster "$cluster_name" --arg namespace "$namespace" --arg verdict "$profile_verdict" \
    --arg runtime_claim "$runtime_claim" --arg environment_safety_result "$environment_safety_result" \
    --arg resource_cleanup_result "$cleanup_result" --arg resource_cleanup_reason "$cleanup_reason" \
    --argjson deadline_seconds "$deadline_seconds" \
    --argjson scenarios "$scenario_json" \
    '{schema_version:($schema_version|tonumber),run_id:$run_id,profile:$profile,
      cluster:$cluster,namespace:$namespace,profile_verdict:$verdict,
      deadline_seconds:$deadline_seconds,
      runtime_resilience_claim:$runtime_claim,
      environment_safety_restoration:$environment_safety_result,
      resource_cleanup_result:$resource_cleanup_result,
      resource_cleanup_reason:$resource_cleanup_reason,scenarios:$scenarios}' \
    >"$evidence_dir/run-result.json"
  {
    printf '# SimpleMatch local resilience run\n\n'
    printf '%s\n' "- profile: $profile" "- run_id: $run_id" "- cluster: $cluster_name" "- namespace: $namespace" "- deadline_seconds: $deadline_seconds" "- verdict: $profile_verdict"
    printf '%s\n' "- runtime_resilience_claim: $runtime_claim" "- environment_safety_restoration: $environment_safety_result" "- resource_cleanup_result: $cleanup_result"
    printf '\n## Scenario results\n\n'
    if [[ "$scenario_json" == '[]' ]]; then
      printf '%s\n' '- none'
    else
      jq -r '.[] | "- \(.scenario_id): \(.execution_verdict) (expected \(.expected_outcome))"' <<<"$scenario_json"
    fi
    printf '\n## Claim boundary\n\n- This report is local evidence only. It is not production HA, automatic failover, or external certification.\n'
  } >"$evidence_dir/report.md"
}

# shellcheck disable=SC2317
cleanup() {
  local status="$1"
  [[ "$dry_run" == true ]] && return
  if [[ "$profile" == full-local && "$namespace_created" == true ]]; then
    if [[ "$keep_resources" == true ]]; then
      cleanup_result=NOT_EVALUATED
    elif resilience_owned_namespace "$namespace" "$run_id" "$context"; then
      if simplematch_kind_delete_disposable_namespace \
          "$context" "$namespace" "$namespace_cleanup_timeout" >/dev/null 2>&1; then
        cleanup_result=PASSED
      else
        cleanup_result=FAILED
      fi
    else
      cleanup_result=FAILED
    fi
  elif [[ "$profile" == contract ]]; then
    cleanup_result=NOT_APPLICABLE
  fi
  [[ "$cleanup_result" == FAILED ]] && profile_verdict=FAILED
  [[ "$status" -ne 0 && "$profile" == contract ]] && profile_verdict=FAILED
  write_result
}

# shellcheck disable=SC2317
on_exit() {
  local status="$?"
  cleanup "$status"
  if [[ "$cleanup_result" == FAILED && "$status" -eq 0 ]]; then
    status=1
  fi
  trap - EXIT
  exit "$status"
}
trap on_exit EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) profile="${2:?--profile requires contract or full-local}"; shift 2 ;;
    --keep-resources) keep_resources=true; shift ;;
    --dry-run) dry_run=true; shift ;;
    --help|-h) usage; trap - EXIT; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
[[ "$profile" == contract || "$profile" == full-local ]] || { usage >&2; exit 2; }
[[ "$namespace_cleanup_timeout" =~ ^[1-9][0-9]*$ ]] || {
  printf '%s\n' 'SIMPLEMATCH_NAMESPACE_CLEANUP_TIMEOUT_SECONDS must be a positive integer.' >&2
  exit 2
}

if [[ "$dry_run" == true ]]; then
  printf 'DRY RUN: profile=%s cluster=%s namespace=%s deadline=%ss\n' "$profile" "$cluster_name" "$namespace" "$deadline_seconds"
  printf '%s\n' 'DRY RUN: target guards -> baseline -> scenario families -> restoration -> evidence -> cleanup.'
  if [[ "$profile" == contract ]]; then
    printf '%s\n' 'DRY RUN: render topology, placement, PDB, rollout, resources, dependency, probe, and log contracts.'
  else
    printf '%s\n' 'DRY RUN: create one lifecycle-labeled disposable namespace; run pod-replacement, planned-disruption, worker-stop sequentially.'
  fi
  trap - EXIT
  exit 0
fi

mkdir -p "$evidence_dir/scenarios" "$evidence_dir/cleanup"

if [[ "$profile" == contract ]]; then
  contract_status=0
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
  for check in "${checks[@]}"; do
    log_name="$(basename "$check" .sh).log"
    if ! bash "$check" >"$evidence_dir/$log_name" 2>&1; then
      contract_status=1
    fi
  done
  if [[ "$contract_status" -eq 0 ]]; then
    profile_verdict=VALIDATED
  else
    profile_verdict=FAILED
  fi
else
  if ! command -v kubectl >/dev/null 2>&1 || ! kubectl --context "$context" get nodes >/dev/null 2>&1; then
    jq -n --arg status UNSUPPORTED '{gate:"baseline",status:$status,reason:"canonical simplematch-live cluster is not available"}' >"$evidence_dir/baseline.json"
    for scenario in "${RESILIENCE_SCENARIOS[@]}"; do
      output="$evidence_dir/scenarios/$scenario.json"
      resilience_write_case_json "$output" "$scenario" UNSUPPORTED UNSUPPORTED NOT_EVALUATED NOT_EVALUATED NOT_EVALUATED \
        'canonical simplematch-live cluster is unavailable in this environment' '' ''
      scenario_json="$(jq -nc --argjson current "$scenario_json" --slurpfile next "$output" '$current + $next')"
    done
    mapfile -t verdicts < <(jq -r '.[].execution_verdict' <<<"$scenario_json")
    profile_verdict="$(resilience_aggregate_full_local NOT_EVALUATED "${verdicts[@]}")"
  else
    if ! simplematch_kind_create_disposable_namespace \
        "$context" "$namespace" local-resilience "$run_id" \
        "simplematch.io/resilience-run=${run_id}"; then
      printf 'Failed to create owned disposable resilience namespace: %s\n' "$namespace" >&2
      exit 1
    fi
    namespace_created=true
    jq -n --arg status NOT_IMPLEMENTED '{gate:"baseline",status:$status,reason:"full-local scenarios are delivered by later #151 tickets"}' >"$evidence_dir/baseline.json"
    for scenario in "${RESILIENCE_SCENARIOS[@]}"; do
      output="$evidence_dir/scenarios/$scenario.json"
      resilience_write_case_json "$output" "$scenario" NOT_IMPLEMENTED NOT_IMPLEMENTED NOT_EVALUATED NOT_EVALUATED NOT_EVALUATED \
        'scenario family is defined by #151 but executable fault actions are not implemented in this slice' '' ''
    scenario_json="$(jq -nc --argjson current "$scenario_json" --slurpfile next "$output" '$current + $next')"
    done
    mapfile -t verdicts < <(jq -r '.[].execution_verdict' <<<"$scenario_json")
    profile_verdict="$(resilience_aggregate_full_local NOT_EVALUATED "${verdicts[@]}")"
  fi
fi

if [[ "$profile" == contract && "$profile_verdict" == VALIDATED ]]; then
  exit 0
fi
if [[ "$profile" == contract ]]; then
  exit 1
fi
if [[ "$profile_verdict" == PASSED ]]; then
  exit 0
fi
if [[ "$profile_verdict" == FAILED ]]; then
  exit 1
fi
exit 2

#!/usr/bin/env bash

set -euo pipefail

# These values are consumed by scripts that source this library.
# shellcheck disable=SC2034

RESILIENCE_SCENARIOS=(pod-replacement planned-disruption worker-stop)
RESILIENCE_EXECUTION_VERDICTS=(PASSED FAILED NOT_IMPLEMENTED UNSUPPORTED SKIPPED BLOCKED)
# shellcheck disable=SC2034
RESILIENCE_COMPONENT_RESULTS=(PASSED FAILED NOT_APPLICABLE NOT_EVALUATED)
# shellcheck disable=SC2034
RESILIENCE_DEFAULT_DEADLINE_SECONDS=300

resilience_deadline() {
  local started_at="$1" timeout_seconds="${2:-300}"
  printf '%s\n' "$((started_at + timeout_seconds))"
}

resilience_remaining_seconds() {
  local started_at="$1" deadline="$2"
  local remaining=$((deadline - started_at))
  (( remaining > 0 )) && printf '%s\n' "$remaining" || printf '0\n'
}

resilience_valid_execution_verdict() {
  local candidate="$1" value
  for value in "${RESILIENCE_EXECUTION_VERDICTS[@]}"; do
    [[ "$candidate" == "$value" ]] && return 0
  done
  return 1
}

resilience_valid_component_result() {
  local candidate="$1" value
  for value in "${RESILIENCE_COMPONENT_RESULTS[@]}"; do
    [[ "$candidate" == "$value" ]] && return 0
  done
  return 1
}

resilience_required_evidence_complete() {
  local required_csv="$1" observed_csv="$2" required observed
  local -a required_items observed_items
  [[ -z "$required_csv" ]] && return 0
  IFS=',' read -r -a required_items <<<"$required_csv"
  IFS=',' read -r -a observed_items <<<"$observed_csv"
  for required in "${required_items[@]}"; do
    observed=false
    for observed_item in "${observed_items[@]}"; do
      [[ "$required" == "$observed_item" ]] && observed=true
    done
    [[ "$observed" == true ]] || return 1
  done
}

resilience_csv_json() {
  [[ -z "$1" ]] && { printf '[]\n'; return; }
  jq -cn --arg csv "$1" '$csv | split(",") | map(select(length > 0))'
}

resilience_select_unique_target() {
  local baseline_json="$1" selector="$2"
  local -a targets
  [[ -f "$baseline_json" ]] || return 1
  mapfile -t targets < <(jq -r "$selector" "$baseline_json")
  [[ ${#targets[@]} -eq 1 && -n "${targets[0]}" && "${targets[0]}" != null ]] || return 1
  printf '%s\n' "${targets[0]}"
}

resilience_log_is_safe() {
  local path="$1"
  [[ -f "$path" ]] || return 1
  ! grep -Eiq '8=FIX|(^|[^[:alnum:]_])(35|49|56|55|54|38|44)=[^[:space:]]+|password[=:]|secret[=:]|credentials?[=:]|raw[_ -]?fix|complete[_ -]?account[_ -]?payload' "$path"
}

resilience_owned_namespace() {
  local namespace="$1" run_id="$2" context="$3"
  kubectl --context "$context" get namespace "$namespace" -o json 2>/dev/null |
    resilience_namespace_json_is_owned "$run_id"
}

resilience_namespace_json_is_owned() {
  local run_id="$1"
  jq -e --arg run_id "$run_id" '
    .metadata.labels["simplematch.io/lifecycle"] == "disposable" and
    .metadata.labels["simplematch.io/managed-by"] == "local-resilience" and
    .metadata.labels["simplematch.io/run-id"] == $run_id and
    .metadata.labels["simplematch.io/resilience-run"] == $run_id
  ' >/dev/null
}

resilience_aggregate_full_local() {
  local cleanup_result="$1" verdict
  shift
  [[ "$cleanup_result" == FAILED ]] && { printf 'FAILED\n'; return; }
  [[ $# -gt 0 ]] || { printf 'INCOMPLETE\n'; return; }
  for verdict in "$@"; do
    [[ "$verdict" == FAILED ]] && { printf 'FAILED\n'; return; }
    [[ "$verdict" == PASSED ]] || { printf 'INCOMPLETE\n'; return; }
  done
  [[ "$cleanup_result" == PASSED ]] && printf 'PASSED\n' || printf 'INCOMPLETE\n'
}

resilience_write_case_json() {
  local output="$1" scenario_id="$2" expected_outcome="$3" execution_verdict="$4"
  local safety_result="$5" recovery_result="$6" restoration_result="$7" limitation="$8"
  local required_evidence="${9}" observed_evidence="${10}" component_result
  resilience_valid_execution_verdict "$execution_verdict" || return 1
  resilience_valid_component_result "$safety_result" || return 1
  resilience_valid_component_result "$recovery_result" || return 1
  resilience_valid_component_result "$restoration_result" || return 1
  if [[ "$execution_verdict" == PASSED ]]; then
    resilience_required_evidence_complete "$required_evidence" "$observed_evidence" || return 1
    for component_result in "$safety_result" "$recovery_result" "$restoration_result"; do
      [[ "$component_result" != FAILED && "$component_result" != NOT_EVALUATED ]] || return 1
    done
  fi
  jq -n \
    --arg scenario_id "$scenario_id" --arg expected_outcome "$expected_outcome" \
    --arg execution_verdict "$execution_verdict" --arg safety_result "$safety_result" \
    --arg recovery_result "$recovery_result" --arg restoration_result "$restoration_result" \
    --arg limitation "$limitation" \
    --argjson required_evidence "$(resilience_csv_json "$required_evidence")" \
    --argjson observed_evidence "$(resilience_csv_json "$observed_evidence")" \
    '{scenario_id:$scenario_id,expected_outcome:$expected_outcome,
      target:null,observed_outcome:null,started_at:null,elapsed_seconds:null,
      execution_verdict:$execution_verdict,safety_result:$safety_result,
      recovery_result:$recovery_result,restoration_result:$restoration_result,
      limitation:$limitation,required_evidence:$required_evidence,
      observed_evidence:$observed_evidence,
      timeline:{fault_requested:null,fault_observed:null,
        expected_outcome_observed:null,restoration_requested:null,
        responsibility_restored:null,representative_operation_passed:null,
        stability_confirmed:null}}' >"$output"
}

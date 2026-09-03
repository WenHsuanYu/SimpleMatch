#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-resilience.sh
source "$script_dir/lib/local-resilience.sh"

bash -n "$script_dir/lib/local-resilience.sh" "$script_dir/run-local-resilience.sh" "$script_dir/validate-local-resilience-contract.sh"

[[ "$(resilience_deadline 100 300)" == 400 ]]
[[ "$RESILIENCE_DEFAULT_DEADLINE_SECONDS" == 300 ]]
[[ "$(resilience_remaining_seconds 150 400)" == 250 ]]
[[ "$(resilience_remaining_seconds 400 400)" == 0 ]]

for verdict in "${RESILIENCE_EXECUTION_VERDICTS[@]}"; do resilience_valid_execution_verdict "$verdict"; done
for result in "${RESILIENCE_COMPONENT_RESULTS[@]}"; do resilience_valid_component_result "$result"; done
resilience_required_evidence_complete pod_uid,node,pvc pod_uid,node,pvc
if resilience_required_evidence_complete pod_uid,node,pvc pod_uid,node; then exit 1; fi
[[ "$(resilience_csv_json pod_uid,node)" == '["pod_uid","node"]' ]]

fixture="$(mktemp "${TMPDIR:-/tmp}/simplematch-resilience-fixture.XXXXXX.json")"
safe_log="$(mktemp "${TMPDIR:-/tmp}/simplematch-resilience-safe.XXXXXX.log")"
unsafe_log="$(mktemp "${TMPDIR:-/tmp}/simplematch-resilience-unsafe.XXXXXX.log")"
case_file="$(mktemp "${TMPDIR:-/tmp}/simplematch-resilience-case.XXXXXX.json")"
namespace_fixture="$(mktemp "${TMPDIR:-/tmp}/simplematch-resilience-namespace.XXXXXX.json")"
trap 'rm -f "$fixture" "$safe_log" "$unsafe_log" "$case_file" "$namespace_fixture"' EXIT

printf '%s\n' '{"targets":[{"id":"worker-0"}]}' >"$fixture"
[[ "$(resilience_select_unique_target "$fixture" '.targets[].id')" == worker-0 ]]
printf '%s\n' '{"targets":[{"id":"worker-0"},{"id":"worker-1"}]}' >"$fixture"
if resilience_select_unique_target "$fixture" '.targets[].id' >/dev/null; then exit 1; fi

printf '%s\n' '{"metadata":{"labels":{"simplematch.io/lifecycle":"disposable","simplematch.io/managed-by":"local-resilience","simplematch.io/run-id":"run-1","simplematch.io/resilience-run":"run-1"}}}' >"$namespace_fixture"
resilience_namespace_json_is_owned run-1 <"$namespace_fixture"
if resilience_namespace_json_is_owned run-2 <"$namespace_fixture"; then exit 1; fi
printf '%s\n' '{"metadata":{"labels":{"simplematch.io/managed-by":"local-resilience","simplematch.io/resilience-run":"run-1"}}}' >"$namespace_fixture"
if resilience_namespace_json_is_owned run-1 <"$namespace_fixture"; then exit 1; fi

printf '%s\n' 'event=consumer_recovered correlation_id=abc-123' >"$safe_log"
printf '%s\n' 'fromApp session=FIX.4.4 msg=8=FIX.4.4|35=D|55=2330 secret=value credentials=secret complete_account_payload=redacted' >"$unsafe_log"
resilience_log_is_safe "$safe_log"
if resilience_log_is_safe "$unsafe_log"; then exit 1; fi
printf '%s\n' '{"event":"connection","password":"secret-value","token":"bearer-value","privateKey":"key-material"}' >"$unsafe_log"
if resilience_log_is_safe "$unsafe_log"; then exit 1; fi
printf '%s\n' 'authorization=Bearer eyJhbGciOiJIUzI1NiJ9.safe-token' >"$unsafe_log"
if resilience_log_is_safe "$unsafe_log"; then exit 1; fi

resilience_write_case_json "$case_file" pod-replacement NOT_IMPLEMENTED NOT_IMPLEMENTED NOT_EVALUATED NOT_EVALUATED NOT_EVALUATED \
  'scenario is not implemented' '' ''
[[ "$(jq -r .execution_verdict "$case_file")" == NOT_IMPLEMENTED ]]
resilience_write_case_json "$case_file" pod-replacement TESTED PASSED PASSED NOT_APPLICABLE PASSED \
  '' pod_uid,node pod_uid,node
[[ "$(jq -r .execution_verdict "$case_file")" == PASSED ]]
[[ "$(jq -r '.timeline.stability_confirmed' "$case_file")" == null ]]
if resilience_write_case_json "$case_file" pod-replacement TESTED PASSED PASSED NOT_APPLICABLE PASSED \
  '' pod_uid,node pod_uid; then exit 1; fi
[[ "$(resilience_aggregate_full_local PASSED PASSED PASSED)" == PASSED ]]
[[ "$(resilience_aggregate_full_local NOT_EVALUATED NOT_IMPLEMENTED)" == INCOMPLETE ]]
[[ "$(resilience_aggregate_full_local PASSED FAILED)" == FAILED ]]

contract_dry_run="$("$script_dir"/run-local-resilience.sh --profile contract --dry-run)"
grep -Fq 'profile=contract' <<<"$contract_dry_run"
grep -Fq 'cannot become a resilience pass' "$script_dir/run-local-resilience.sh"
full_local_dry_run="$("$script_dir"/run-local-resilience.sh --profile full-local --dry-run)"
grep -Fq 'pod-replacement, planned-disruption, worker-stop' <<<"$full_local_dry_run"
grep -Fq 'lifecycle-labeled disposable namespace' <<<"$full_local_dry_run"

printf '%s\n' 'Local resilience runner contract passed.'

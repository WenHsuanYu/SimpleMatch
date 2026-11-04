#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/test-interfaces.sh
source "$script_dir/../lib/test-interfaces.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/kafka-observation-interface.sh
source "$script_dir/../lib/kafka-observation-interface.sh"

gateway_env_modified=false
restoration_failed=false
timeout_seconds=1
kns() {
  # Test the external kubectl arguments, without printing the operator token.
  case "$1" in
    get) return 0 ;;
    create|patch) cat >/dev/null; return 0 ;;
    set)
      case " $* " in
        *'SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_AUTOMATIC_CLOSE_ENABLED=true'*) [[ "$expected_close" == true ]] ;;
        *'SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_AUTOMATIC_CLOSE_ENABLED=false'*) [[ "$expected_close" == false ]] ;;
        *) return 0 ;;
      esac ;;
    rollout) return 0 ;;
    *) return 1 ;;
  esac
}
die() { printf '%s\n' "$*" >&2; exit 1; }

expected_close=true
enable_gateway_operations true
[[ "$gateway_env_modified" == true && -n "$gateway_operator_token" ]]
restore_gateway_environment
[[ "$gateway_env_modified" == false && "$restoration_failed" == false ]]
expected_close=false
enable_gateway_operations
restore_gateway_environment
printf '%s\n' 'Gateway adapter preserves automatic close and restores optional overrides.'

temporary_directory="$(mktemp -d /tmp/simplematch-live-open.XXXXXX)"
trap 'rm -rf -- "$temporary_directory"' EXIT
timeout_seconds=3
attempts=0
mode=open
test_epoch=0
date() { printf '%s\n' "$test_epoch"; }
sleep() { test_epoch=$((test_epoch + 1)); }
gateway_request() {
  local method="$1" destination="$3" fixture
  if [[ "$method" == POST ]]; then
    [[ "$mode" == open ]] || die 'status observation must not reopen Gateway'
    attempts=$((attempts + 1))
    fixture=waiting
    (( attempts < 3 )) || fixture=opened
  elif [[ "$mode" == observe ]]; then
    attempts=$((attempts + 1))
    fixture=unready-open
    (( attempts < 3 )) || fixture=opened
  elif [[ "$mode" == paused ]]; then
    fixture=waiting
  else
    fixture=eligible
  fi
  cp "$script_dir/fixtures/live-gateway/$fixture.json" "$destination"
}
open_gateway_from_live_observations request.json "$temporary_directory/before.json" "$temporary_directory/open.json"
[[ "$attempts" == 3 ]]
mode=observe
attempts=0
wait_gateway_live_open "$temporary_directory/after.json"
[[ "$attempts" == 3 ]]
mode=paused
timeout_seconds=2
if wait_gateway_live_open "$temporary_directory/timeout.json"; then
  die 'a paused Gateway must not be treated as OPEN after the deadline'
fi
printf '%s\n' 'Live Gateway retries real operator open; final observation never reopens a paused gate.'

stop_background_process() { return 0; }
start_port_forward() {
  [[ "$1" == service/quickfix-gateway-owner-0 && "$2" == 5001 && "$6" == 45678 ]]
  fix_port="$6"
}
evidence_dir="$temporary_directory"
start_fix_port_forward 45678
[[ "$fix_port" == 45678 ]]
printf '%s\n' 'FIX interface rebinds the stable owner Service to the retained client port.'

stop_background_process() { return 1; }
fix_port_forward_pid=12345
gateway_port_forward_pid=23456
gateway_port=45679
kafka_observer_port_forward_pid=34567
kafka_observer_port=45680
if stop_fix_port_forward; then
  die 'failed FIX tunnel cleanup must propagate failure'
fi
[[ "$fix_port_forward_pid" == 12345 && "$fix_port" == 45678 ]]
if stop_gateway_port_forward; then
  die 'failed Gateway tunnel cleanup must propagate failure'
fi
[[ "$gateway_port_forward_pid" == 23456 && "$gateway_port" == 45679 ]]
if stop_kafka_observation_adapter; then
  die 'failed Kafka observer tunnel cleanup must propagate failure'
fi
[[ "$kafka_observer_port_forward_pid" == 34567 && "$kafka_observer_port" == 45680 ]]
start_port_forward() { die 'a failed tunnel cleanup must refuse a replacement'; }
prepare_kafka_observer_manifest() { return 0; }
create_kafka_observer_pod() { return 0; }
kafka_observer_pod=contract-observer
if start_fix_port_forward 45678 || start_gateway_port_forward || start_kafka_observation_adapter contract-evidence; then
  die 'a stale tunnel must not be replaced after cleanup fails'
fi
[[ "$fix_port_forward_pid" == 12345 && "$gateway_port_forward_pid" == 23456 &&
    "$kafka_observer_port_forward_pid" == 34567 ]]
stop_background_process() { return 0; }
stop_fix_port_forward
stop_gateway_port_forward
stop_kafka_observation_adapter
[[ -z "$fix_port_forward_pid" && -z "$fix_port" &&
    -z "$gateway_port_forward_pid" && -z "$gateway_port" &&
    -z "$kafka_observer_port_forward_pid" && -z "$kafka_observer_port" ]]
printf '%s\n' 'Tunnel cleanup propagates failure and retains identity for a later cleanup attempt.'

# The optional absolute deadline belongs to one scenario, not to each I/O retry.
# shellcheck source=scripts/end-to-end/critical-consumers/lib/cluster-data.sh
source "$script_dir/../lib/cluster-data.sh"
date() { printf '%s\n' 1000; }
operation_deadline_epoch_ms=1500
[[ "$(bounded_operation_timeout_seconds 15)" == 0.500 ]]
operation_deadline_epoch_ms=1000
if bounded_operation_timeout_seconds 15; then
  die 'expired overall deadline must refuse the next I/O'
fi
unset operation_deadline_epoch_ms
[[ "$(bounded_operation_timeout_seconds 15)" == 15 ]]
printf '%s\n' 'Bounded I/O uses the remaining total time; ordinary calls keep their own limits.'

# Exercise the shared prepared-client process and completion protocol through its CLI boundary.
(
  unset -f date sleep timeout
  repo_root="$temporary_directory/prepared-client"
  evidence_dir="$temporary_directory/prepared-evidence"
  mkdir -p "$repo_root" "$evidence_dir/fix"
  cat >"$repo_root/gradlew" <<'CLIENT'
#!/usr/bin/env bash
set -euo pipefail
printf 'ready\n' >"$SIMPLEMATCH_RETAINED_FIX_READY_FILE"
until [[ -f "$SIMPLEMATCH_RETAINED_FIX_RELEASE_FILE" ]]; do sleep 0.02; done
printf '{"clOrdId":"TEST-ORDER"}\n' >"$SIMPLEMATCH_RETAINED_FIX_EVIDENCE"
until [[ -f "$SIMPLEMATCH_RETAINED_FIX_COMPLETION_FILE" ]]; do sleep 0.02; done
[[ "$(<"$SIMPLEMATCH_RETAINED_FIX_COMPLETION_FILE")" == TEST-FINAL-EXEC ]]
printf '{"clOrdId":"TEST-ORDER","execId":"TEST-FINAL-EXEC"}\n' \
  >"$SIMPLEMATCH_RETAINED_FIX_COMPLETION_EVIDENCE"
CLIENT
  chmod +x "$repo_root/gradlew"
  SIMPLEMATCH_RETAINED_FIX_STATE_DIR="$temporary_directory/shared-fix-state"
  prepare_fix_state_directory
  fix_state_dir="${fix_state_dir:?prepared FIX state directory missing}"
  printf 'retained-sequence-ledger\n' >"$fix_state_dir/ledger"
  fix_wait_for_delivery=true
  gateway_recovery=false
  matching_recovery=false
  fix_port=12345 account_id=test-account symbol=test quantity=1 price=1 cl_ord_id=TEST-ORDER
  timeout_seconds=5
  start_fix_submit_client
  fix_completion_file="${fix_completion_file:?prepared FIX completion file missing}"
  release_fix_submit_client
  wait_fix_submission_evidence
  kill -0 "$fix_submit_pid"
  for intent in pending unrelated; do
    jq -n --arg status "$([[ "$intent" == pending ]] && echo PENDING || echo SENT)" \
      --arg clOrdId "$([[ "$intent" == unrelated ]] && echo ANOTHER-ORDER || echo TEST-ORDER)" \
      '{status:$status,clientOrderId:$clOrdId,execId:"TEST-FINAL-EXEC"}' \
      >"$evidence_dir/intent.json"
    if complete_fix_submit_client "$evidence_dir/intent.json"; then
      die 'pending or unrelated delivery must not release the prepared client'
    fi
    [[ ! -e "$fix_completion_file" ]]
  done
  printf '{"status":"SENT","clientOrderId":"TEST-ORDER","execId":"TEST-FINAL-EXEC"}\n' \
    >"$evidence_dir/intent.json"
  complete_fix_submit_client "$evidence_dir/intent.json"
  [[ -z "$fix_submit_pid" ]]
  remove_owned_fix_state_directory
  [[ "$(<"$fix_state_dir/ledger")" == retained-sequence-ledger ]]
  unset SIMPLEMATCH_RETAINED_FIX_STATE_DIR
  prepare_fix_state_directory
  owned_state="$fix_state_dir"
  remove_owned_fix_state_directory
  [[ ! -d "$owned_state" ]]
)
printf '%s\n' 'Prepared completion requires correlated SENT evidence and preserves caller-owned FIX state.'

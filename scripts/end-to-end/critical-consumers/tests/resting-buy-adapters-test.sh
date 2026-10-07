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
printf '%s\n' 'Normal flow preserves automatic close; legacy fault scenarios retain their explicit override.'

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
printf '%s\n' 'Recovery rebinds the stable owner Service to the retained client port.'

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
printf '%s\n' 'Recovery I/O uses remaining total time; existing scenarios keep their own limits.'

# Reject a timed-out PV query through the same hard-kill contract as namespaced I/O.
# shellcheck source=scripts/end-to-end/critical-consumers/lib/gateway-owner-recovery.sh
source "$script_dir/../lib/gateway-owner-recovery.sh"
fix_state_dir="$temporary_directory/private"
context="kind-contract-test"
mkdir -p "$evidence_dir/recovery"
kns() {
  [[ "$1" == get ]]
  jq --arg resource "$2" '.[$resource]' "$script_dir/fixtures/gateway-owner-resources.json"
}
pv_timeout_checked=false
timeout() {
  [[ "$1" == --foreground && "$2" == --signal=TERM && "$3" == --kill-after=2s &&
      "$4" == 10s && "$5" == kubectl ]] || die 'PV query must have TERM-to-KILL timeout escalation'
  pv_timeout_checked=true
  return 1
}
if capture_gateway_recovery_state before; then
  die 'a failed PV query must reject the recovery observation'
fi
[[ "$pv_timeout_checked" == true ]]
printf '%s\n' 'PV continuity reads escalate TERM to KILL and propagate query failure.'

# Gateway's shell-less runtime is observed through its validated kind-local PV.
private_resources="$fix_state_dir/owner-resources"
jq '.pv' "$script_dir/fixtures/gateway-owner-resources.json" >"$private_resources/pv.json"
test_node_cluster="${context#kind-}"
docker() {
  case "$1" in
    inspect) printf '%s\n' "$test_node_cluster" ;;
    exec)
      [[ "$2" == worker-1 && "$3" == cat &&
          "$4" == /var/local-path-provisioner/PRIVATE_TEST_VOLUME/wal/inbound.wal ]] ||
        die 'WAL reads must use only the actual owner node and validated local PV'
      printf '%s\n' 'redacted-test-wal-line' ;;
    *) return 1 ;;
  esac
}
timeout() {
  [[ "$1" == --foreground && "$2" == --signal=TERM && "$3" == --kill-after=2s &&
      "$4" == 10s && "$5" == docker ]] || die 'node-local WAL reads must remain bounded'
  shift 4
  "$@"
}
read_gateway_recovery_file "$private_resources" inbound.wal >"$temporary_directory/wal-read.txt"
[[ "$(<"$temporary_directory/wal-read.txt")" == redacted-test-wal-line ]]
if read_gateway_recovery_file "$private_resources" ../../another-file; then
  die 'WAL observation must refuse unrelated files'
fi
test_node_cluster=unrelated-cluster
if read_gateway_recovery_file "$private_resources" inbound.wal; then
  die 'WAL observation must refuse a node from another kind cluster'
fi
test_node_cluster="${context#kind-}"
jq '.pv | .spec.hostPath.path = "/unrelated-volume"' "$script_dir/fixtures/gateway-owner-resources.json" >"$private_resources/pv.json"
if read_gateway_recovery_file "$private_resources" inbound.wal; then
  die 'WAL observation must refuse an unsupported storage root'
fi
printf '%s\n' 'WAL reads use the exact owned local PV, with no shell dependency in Gateway.'

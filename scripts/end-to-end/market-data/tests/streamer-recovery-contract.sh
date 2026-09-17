#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
runner="$script_dir/../run-streamer-recovery-certification.sh"
interfaces="$script_dir/../../critical-consumers/lib/test-interfaces.sh"
lifecycle="$script_dir/../lib/streamer-recovery-lifecycle.sh"
verdict_writer="$script_dir/../lib/streamer-recovery-verdict.sh"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-streamer-recovery-contract.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT

fail() {
  printf 'Market-data streamer recovery contract: %s\n' "$*" >&2
  exit 1
}

assert_equal() {
  local actual="$1"
  local expected="$2"
  local description="$3"
  [[ "$actual" == "$expected" ]] ||
    fail "$description (expected '$expected', got '$actual')"
}

join_events() {
  local IFS=,
  printf '%s' "${events[*]}"
}

[[ -x "$runner" ]] || fail 'streamer recovery runner must be executable'
[[ -f "$interfaces" && -f "$lifecycle" && -f "$verdict_writer" ]] ||
  fail 'streamer recovery helpers must exist'

bash -n "$runner" "$interfaces" "$lifecycle" "$verdict_writer"

# Exercise the phase-specific tunnel paths without starting Kubernetes.
# shellcheck source=scripts/end-to-end/critical-consumers/lib/test-interfaces.sh
source "$script_dir/../../critical-consumers/lib/test-interfaces.sh"
# shellcheck source=scripts/end-to-end/market-data/lib/streamer-recovery-lifecycle.sh
source "$lifecycle"
# shellcheck source=scripts/end-to-end/market-data/lib/streamer-recovery-verdict.sh
source "$verdict_writer"

# Exercise the verdict writer with distinct timestamps without starting Kubernetes.
verdict_input="$temporary_directory/verdict-input.json"
verdict_output="$temporary_directory/verdict.json"
jq -n \
  --arg sourceRevision test-revision \
  --arg namespace test-namespace \
  --arg context test-context \
  --arg oldUid old-pod \
  --arg oldNode old-worker \
  --arg newUid new-pod \
  --arg newNode new-worker \
  --argjson oldPodGoneEpochMs 1000 \
  --argjson newPodReadyEpochMs 2000 \
  --argjson expectedPartitions 15 \
  '{sourceRevision:$sourceRevision,namespace:$namespace,context:$context,
    oldUid:$oldUid,oldNode:$oldNode,newUid:$newUid,newNode:$newNode,
    oldPodGoneEpochMs:$oldPodGoneEpochMs,newPodReadyEpochMs:$newPodReadyEpochMs,
    expectedPartitions:$expectedPartitions}' >"$verdict_input"
write_streamer_recovery_verdict "$verdict_output" <"$verdict_input"
assert_equal "$(jq -er '.streamer.newPodReadyEpochMs' "$verdict_output")" \
  '2000' 'verdict must retain the replacement readiness timestamp'
assert_equal "$(jq -er '.streamer.noOverlap' "$verdict_output")" \
  'true' 'verdict must derive no-overlap from the two timestamps'

evidence_dir="$temporary_directory/evidence"
requested_local_port=47111
mkdir -p "$evidence_dir/signals"

forward_logs=()
forward_ports=()
events=()
start_port_forward() {
  forward_logs+=("$3")
  forward_ports+=("${6:-}")
  case "$1" in
    service/market-data-projection)
      events+=("start-projection-tunnel:$3")
      ;;
    service/marketdata-streamer)
      events+=("start-streamer-tunnel:$3:${6:-}")
      streamer_port="${6:-}"
      ;;
  esac
}

start_projection_port_forward "$evidence_dir" setup
start_projection_port_forward "$evidence_dir" initial
start_projection_port_forward "$evidence_dir" resubscribed
assert_equal "${forward_logs[0]}" \
  "$evidence_dir/diagnostics/projection-port-forward-setup.log" \
  'projection setup must have its own log'
assert_equal "${forward_logs[1]}" \
  "$evidence_dir/diagnostics/projection-port-forward-initial-replay.log" \
  'initial projection replay must have its own log'
assert_equal "${forward_logs[2]}" \
  "$evidence_dir/diagnostics/projection-port-forward-resubscribed-replay.log" \
  'resubscribed projection replay must have its own log'
[[ "${forward_logs[0]}" != "${forward_logs[1]}" &&
   "${forward_logs[1]}" != "${forward_logs[2]}" ]] ||
  fail 'projection tunnel lifecycles must not overwrite one another'

forward_logs=()
forward_ports=()
start_streamer_port_forward "$evidence_dir" initial
start_streamer_port_forward "$evidence_dir" replacement "$requested_local_port"
assert_equal "${forward_logs[0]}" \
  "$evidence_dir/diagnostics/streamer-port-forward-initial.log" \
  'initial streamer tunnel must have its own log'
assert_equal "${forward_logs[1]}" \
  "$evidence_dir/diagnostics/streamer-port-forward-replacement.log" \
  'replacement streamer tunnel must have its own log'
assert_equal "${forward_ports[1]}" "$requested_local_port" \
  'replacement streamer tunnel must preserve the observer local port'

# Exercise the projection restart lifecycle itself; the readiness barrier must
# occur before the stale tunnel is retired and the new tunnel is started.
events=()
reset_projection_state() { events+=("reset-state:$1"); }
reset_projection_offsets() { events+=("reset-offsets:$1"); }
kns() {
  [[ "$*" == 'rollout restart deployment/market-data-projection' ]] ||
    fail "unexpected projection command: $*"
  events+=(rollout-restart)
}
wait_deployment_replicas() { events+=("ready:$1:$2"); }
stop_projection_port_forward() { events+=(stop-projection-tunnel); }
produce_projection_snapshot "$evidence_dir" initial
assert_equal "$(join_events)" \
  "reset-state:initial,reset-offsets:initial,rollout-restart,ready:market-data-projection:1,stop-projection-tunnel,start-projection-tunnel:$evidence_dir/diagnostics/projection-port-forward-initial-replay.log" \
  'projection replay must reopen its tunnel after readiness'

# Exercise the streamer replacement barrier; readiness is published only
# after the old tunnel has been replaced on the same localhost port.
events=()
streamer_port="$requested_local_port"
stop_streamer_port_forward() {
  events+=(stop-streamer-tunnel)
  streamer_port=''
}
publish_streamer_replacement_ready "$evidence_dir"
assert_equal "$(join_events)" \
  "stop-streamer-tunnel,start-streamer-tunnel:$evidence_dir/diagnostics/streamer-port-forward-replacement.log:$requested_local_port" \
  'streamer replacement must refresh its tunnel before publication'
[[ -f "$evidence_dir/signals/replacement.ready" ]] ||
  fail 'replacement readiness signal must be published after the refresh'

# Exercise the shared port-forward helper with the real process/liveness loop.
(
  # shellcheck source=scripts/end-to-end/critical-consumers/lib/test-interfaces.sh
  source "$script_dir/../../critical-consumers/lib/test-interfaces.sh"
  child_pid_file="$temporary_directory/port-forward-child.pid"
  kns() {
    [[ "${1:-}" == --probe ]] && return 0
    [[ "$*" == "port-forward service/marketdata-streamer ${requested_local_port}:50053" ]] ||
      return 99
    printf 'Forwarding from 127.0.0.1:%s -> 50053\n' "$requested_local_port"
    while :; do sleep 1; done &
    child_pid="$!"
    printf '%s\n' "$child_pid" >"$child_pid_file"
    wait "$child_pid"
  }
  kns --probe
  local_log="$temporary_directory/port-forward.log"
  local_port=''
  port_forward_pid=''
  cleanup_port_forward() {
    stop_background_process "${port_forward_pid:-}"
    port_forward_pid=''
  }
  trap cleanup_port_forward EXIT
  start_port_forward service/marketdata-streamer 50053 \
    "$local_log" port_forward_pid local_port "$requested_local_port"
  assert_equal "$local_port" "$requested_local_port" \
    'port-forward helper must report the requested local port'
  cleanup_port_forward
  trap - EXIT
  child_pid="$(<"$child_pid_file")"
  if kill -0 "$child_pid" >/dev/null 2>&1; then
    kill "$child_pid" >/dev/null 2>&1 || true
    fail 'stopping a port-forward must terminate its child process'
  fi
)

printf '%s\n' 'Market-data streamer recovery contract is valid.'

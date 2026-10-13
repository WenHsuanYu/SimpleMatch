#!/usr/bin/env bash
set -Eeuo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/business-recovery-lifecycle.sh
source "$script_dir/lib/business-recovery-lifecycle.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/cluster-data.sh
source "$script_dir/lib/cluster-data.sh"

die() { printf '%s\n' "$*" >&2; exit 1; }
test_epoch_ms=1000
date() { printf '%s\n' "$test_epoch_ms"; }
timeout_seconds=3

# Both business paths borrow one absolute budget; restoration gets its original
# timeout even after expiry, and repeated cleanup cannot restore stale state.
for scenario in Gateway 'Matching business'; do
  begin_business_recovery "$scenario"
  test_epoch_ms=$((test_epoch_ms + 2500))
  [[ "$(business_recovery_remaining_seconds)" == 1 ]]
  [[ "$(bounded_operation_timeout_seconds 10)" == 0.500 ]]
  timeout_seconds=1
  test_epoch_ms=$((test_epoch_ms + 500))
  if (business_recovery_remaining_seconds) >/dev/null 2>&1; then
    die 'expired recovery must refuse the next step'
  fi
  finish_business_recovery
  [[ "$timeout_seconds" == 3 && "$(bounded_operation_timeout_seconds 10)" == 10 ]]
  timeout_seconds=7
  finish_business_recovery
  [[ "$timeout_seconds" == 7 ]]
  timeout_seconds=3
done
printf '%s\n' 'Recovery shares one deadline and restores its borrowed timeout exactly once.'

# A real background observer belongs to recovery, while a baseline process does
# not. A failed stop must retain enough identity for the next cleanup to reap it.
source "$script_dir/lib/test-interfaces.sh"
temporary_directory="$(mktemp -d /tmp/simplematch-recovery-lifecycle.XXXXXX)"
baseline_pid=""
trap 'source "$script_dir/lib/test-interfaces.sh"; finish_business_recovery; stop_background_process "$baseline_pid"; rm -rf -- "$temporary_directory"' EXIT
evidence_dir="$temporary_directory"
mkdir -p "$evidence_dir/recovery"
sample_gateway_recovery_owners() {
  printf '%s\n' "$BASHPID" >"$temporary_directory/sampler-pid"
  while :; do sleep 1; done
}
sleep 60 &
baseline_pid="$!"
begin_business_recovery Gateway
start_gateway_recovery_sampling
for _ in $(seq 1 100); do
  [[ ! -s "$temporary_directory/sampler-pid" ]] || break
  sleep 0.01
done
sampled_pid="$(cat "$temporary_directory/sampler-pid")"
background_process_is_alive "$sampled_pid"
stop_background_process() { [[ -z "${1:-}" ]]; }
if complete_gateway_recovery_sampling; then
  die 'failed sampler stop must fail recovery'
fi
if finish_business_recovery; then
  die 'failed sampler stop must fail restoration'
fi
[[ "$timeout_seconds" == 3 && "$(bounded_operation_timeout_seconds 10)" == 10 ]]
source "$script_dir/lib/test-interfaces.sh"
finish_business_recovery
if background_process_is_alive "$sampled_pid"; then
  die 'cleanup lost the sampler identity after a failed stop'
fi
background_process_is_alive "$baseline_pid"
finish_business_recovery
printf '%s\n' 'Recovery reaps its sampler after a failed stop and leaves baseline processes alive.'

# The ordinary completion path uses the same stop contract. An observer that
# exited early cannot be accepted as successful sampling.
rm "$temporary_directory/sampler-pid"
begin_business_recovery Gateway
start_gateway_recovery_sampling
for _ in $(seq 1 100); do
  [[ ! -s "$temporary_directory/sampler-pid" ]] || break
  sleep 0.01
done
sampled_pid="$(cat "$temporary_directory/sampler-pid")"
complete_gateway_recovery_sampling
finish_business_recovery
if background_process_is_alive "$sampled_pid"; then
  die 'normal completion left its observer alive'
fi
sample_gateway_recovery_owners() { return 1; }
begin_business_recovery Gateway
start_gateway_recovery_sampling
wait "$!" || true
if (complete_gateway_recovery_sampling) >/dev/null 2>&1; then
  die 'an observer that ended early cannot satisfy recovery'
fi
finish_business_recovery
printf '%s\n' 'Normal recovery reaps its observer; premature observer exit refuses success.'

#!/usr/bin/env bash

# One business recovery borrows the runner's timeout. Baseline resources and the
# terminal EXIT trap remain runner-owned; restoration has its original budget.
begin_business_recovery() {
  recovery_scenario="$1"
  recovery_original_timeout_seconds="$timeout_seconds"
  recovery_started_ms="$(date +%s%3N)" || return 1
  recovery_budget_ms=$((timeout_seconds * 1000))
  operation_deadline_epoch_ms=$((recovery_started_ms + recovery_budget_ms))
  kubernetes_request_timeout_seconds=10
}

business_recovery_remaining_seconds() {
  local remaining=$((operation_deadline_epoch_ms - $(date +%s%3N)))
  (( remaining > 0 )) || die "$recovery_scenario recovery exceeded its single deadline"
  printf '%s\n' "$(((remaining + 999) / 1000))"
}

start_gateway_recovery_sampling() {
  sample_gateway_recovery_owners >"$evidence_dir/recovery/owner-samples.jsonl" &
  recovery_owner_sampler_pid="$!"
}

# Clear identity only after successful stopping, so the terminal cleanup can
# retry a failed stop. The observer is the only recovery-created process.
stop_recovery_owner_sampler() {
  [[ -n "${recovery_owner_sampler_pid:-}" ]] || return 0
  stop_background_process "$recovery_owner_sampler_pid" || return 1
  recovery_owner_sampler_pid=""
}

complete_gateway_recovery_sampling() {
  background_process_is_alive "$recovery_owner_sampler_pid" || die 'Gateway owner observation ended unexpectedly'
  stop_recovery_owner_sampler
}

# Safe before recovery starts and on repeated cleanup: never restore an old run's
# timeout after its state has already been released.
finish_business_recovery() {
  if [[ -n "${recovery_original_timeout_seconds:-}" ]]; then
    timeout_seconds="$recovery_original_timeout_seconds"
    unset recovery_original_timeout_seconds
  fi
  kubernetes_request_timeout_seconds=""
  operation_deadline_epoch_ms=""
  stop_recovery_owner_sampler
}

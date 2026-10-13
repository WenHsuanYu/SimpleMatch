#!/usr/bin/env bash
set -Eeuo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

# Each case is a fresh shell, exercising the real recovery entry and its terminal
# cleanup notification. Only external observations/mutations are substituted.
if [[ "${1:-}" == case ]]; then
  scenario="$2" failure="$3" invocation="$4" evidence_dir="$5"
  source "$script_dir/lib/gateway-owner-recovery.sh"
  source "$script_dir/lib/matching-business-recovery.sh"
  source "$script_dir/lib/test-interfaces.sh"
  source "$script_dir/lib/cluster-data.sh"
  timeout_seconds=3
  fix_port=45678
  fix_state_dir="$evidence_dir"
  partition=4
  die() { printf '%s\n' "$*" >&2; exit 1; }
  cleanup() {
    local status="$?"
    trap - EXIT
    set +e
    finish_business_recovery || status=1
    # Observe restoration through the same budget adapter used by I/O.
    printf '%s %s\n' "$timeout_seconds" "$(bounded_operation_timeout_seconds 10)" >"$evidence_dir/restored"
    printf '%s\n' "$status" >"$evidence_dir/status"
    exit "$status"
  }
  trap cleanup EXIT
  capture_gateway_recovery_state() {
    [[ "$failure" != before ]] || return 1
    printf '%s\n' '{"podUid":"original-owner"}' >"$evidence_dir/recovery/before-owner.json"
  }
  capture_matching_owner() {
    [[ "$failure" != before ]] || return 1
    printf '%s\n' '{"podUid":"original-owner","ready":true}' >"$evidence_dir/matching-recovery/before-owner.json"
  }
  kns() {
    if [[ "$1" == delete ]]; then
      printf '%s\n' delete >>"$evidence_dir/actions"
      [[ "$failure" != delete ]] || return 1
      return 0
    fi
    return 1
  }
  wait_gateway_replacement_ready() {
    printf '%s\n' wait >>"$evidence_dir/actions"
    return 1
  }
  # This slow background observer must be reaped on a post-mutation failure.
  sample_gateway_recovery_owners() {
    printf '%s\n' "$BASHPID" >"$evidence_dir/sampler-pid"
    while :; do sleep 1; done
  }
  entry=run_gateway_owner_recovery
  [[ "$scenario" == Gateway ]] || entry=run_matching_business_recovery
  if [[ "$invocation" == conditional ]]; then
    if "$entry"; then
      printf '%s\n' unexpected-success >"$evidence_dir/completed"
    else
      exit 1
    fi
  else
    "$entry"
  fi
  printf '%s\n' unexpected-success >"$evidence_dir/completed"
  exit 0
fi

temporary_directory="$(mktemp -d /tmp/simplematch-recovery-failure.XXXXXX)"
trap 'rm -rf -- "$temporary_directory"' EXIT
for scenario in Gateway Matching; do
  for failure in before delete after; do
    for invocation in direct conditional; do
      evidence="$temporary_directory/$scenario-$failure-$invocation"
      mkdir -p "$evidence"
      if bash "${BASH_SOURCE[0]}" case "$scenario" "$failure" "$invocation" "$evidence" >"$evidence/log" 2>&1; then
        cat "$evidence/log" >&2
        exit 1
      fi
      [[ ! -e "$evidence/completed" && "$(cat "$evidence/restored")" == '3 10' ]]
      [[ "$(cat "$evidence/status")" != 0 ]]
      if [[ "$failure" == before ]]; then
        [[ ! -e "$evidence/actions" ]]
      elif [[ "$failure" == delete ]]; then
        [[ "$(cat "$evidence/actions")" == delete ]]
      fi
      if [[ -s "$evidence/sampler-pid" ]]; then
        if kill -0 "$(cat "$evidence/sampler-pid")" 2>/dev/null; then
          printf '%s\n' 'recovery left its sampler alive' >&2
          exit 1
        fi
      fi
    done
  done
done
printf '%s\n' 'Both recovery entries stop on failure, restore timeouts, and reap their observer.'

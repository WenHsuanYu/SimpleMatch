#!/usr/bin/env bash
set -Eeuo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/.."
# shellcheck source=scripts/end-to-end/critical-consumers/lib/matching-business-recovery.sh
source "$script_dir/lib/matching-business-recovery.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/cluster-data.sh
source "$script_dir/lib/cluster-data.sh"
temporary_directory="$(mktemp -d /tmp/simplematch-matching-adapters.XXXXXX)"
trap 'rm -rf -- "$temporary_directory"' EXIT
evidence_dir="$temporary_directory/evidence"
fix_state_dir="$temporary_directory/private"
mkdir -p "$evidence_dir/matching-recovery" "$fix_state_dir"
context='kind-contract-test'
namespace='contract-test'
matching_owner=matching-4
matching_original_uid=matching-original-uid
partition=4
timeout_seconds=10
matching_recovery_deadline_ms=$(( $(date +%s%3N) + 10000 ))
die() { printf '%s\n' "$*" >&2; exit 1; }

# Historical day is selected from retained provenance, never from today's clock.
retained_evidence_dir="$temporary_directory/retained"
mkdir -p "$retained_evidence_dir"
cp "$script_dir/tests/fixtures/matching-run-context" "$retained_evidence_dir/run-context"
unset SIMPLEMATCH_CERTIFICATION_TRADING_DAY
[[ "$(matching_recovery_trading_day "$retained_evidence_dir")" == 2026-08-27 ]]
SIMPLEMATCH_CERTIFICATION_TRADING_DAY=2026-08-27
[[ "$(matching_recovery_trading_day "$retained_evidence_dir")" == 2026-08-27 ]]
SIMPLEMATCH_CERTIFICATION_TRADING_DAY=2026-10-09
if matching_recovery_trading_day "$retained_evidence_dir"; then
  die 'an explicit different day must not replace the retained artifact day'
fi
unset SIMPLEMATCH_CERTIFICATION_TRADING_DAY
printf '%s\n' 'trading_day=2026-02-30' >"$retained_evidence_dir/run-context"
if matching_recovery_trading_day "$retained_evidence_dir"; then
  die 'invalid retained calendar day must be rejected'
fi
cp "$script_dir/tests/fixtures/matching-run-context" "$retained_evidence_dir/run-context"

# A failed inventory must stop before the first delete, even in a conditional caller.
kns() { return 1; }
if run_matching_business_recovery; then
  die 'failed original owner observation must refuse the mutation'
fi
[[ ! -e "$evidence_dir/matching-recovery/restart.log" ]]

# The first delete targets only the partition selected from the real command.
kns() {
  case "$1" in
    delete) [[ "$2" == pod && "$3" == matching-4 && "$4" == --wait=false ]] ;;
    get)
      if [[ "$2" == pods ]]; then
        [[ "$4" == statefulset.kubernetes.io/pod-name=matching-4 ]]
        printf '%s\n' '{"items":[]}'
      elif [[ " $* " == *'.metadata.uid'* ]]; then
        printf '%s\n' matching-replacement-uid
      else
        printf '%s\n' True
      fi ;;
    *) return 1 ;;
  esac
}
replace_matching_owner
jq -e '.originalPodUid == "matching-original-uid" and .oldOwnerInterrupted == true' \
  "$evidence_dir/matching-recovery/interruption.json" >/dev/null

# Query errors after deletion cannot be converted into evidence that the owner disappeared.
kns() { [[ "$1" == delete ]]; }
if replace_matching_owner; then
  die 'failed post-delete observation must reject owner recovery'
fi

# Warm/helper Java I/O receives the remaining single deadline, not a fresh timeout per step.
kafka_observer_pod=contract-observer
order_id=0198a000-0000-7000-8000-000000000002
kns() {
  [[ " $* " == *'MatchingEventRedeliveryMain'* && " $* " == *'--partition 4'* &&
      " $* " == *'--start-offset 1'* ]] || return 1
  [[ "$kubernetes_request_timeout_seconds" -gt 0 && "$kubernetes_request_timeout_seconds" -le 10 ]]
  return 1
}
if run_matching_kafka_helper MatchingEventRedeliveryMain 0198a000-0000-7000-8000-000000000005 1; then
  die 'failed redelivery helper must propagate failure'
fi

if bash "$script_dir/run-resting-buy-certification.sh" --namespace contract \
    --evidence-dir "$temporary_directory/invalid" --gateway-recovery --matching-recovery \
    >"$temporary_directory/conflict.log" 2>&1; then
  die 'mixed recovery options must be rejected before deployment access'
fi
rg -q 'choose one recovery scenario' "$temporary_directory/conflict.log"
printf '%s\n' 'Matching recovery fails closed on observation/helper errors and mutates only the routed owner.'

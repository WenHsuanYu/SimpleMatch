#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
evidence_module="$script_dir/../lib/evidence.sh"

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

source "$evidence_module"
jq -n '{topic:"matching.events",partitions:[range(0;15) |
  {partition:.,offset:(100 + .)}]}' >"$temporary_directory/boundary.json"
write_query_consumer_reset_file \
  "$temporary_directory/boundary.json" \
  "$temporary_directory/reset.csv"
[[ "$(sed -n '1p' "$temporary_directory/reset.csv")" == 'matching.events,0,100' ]] || {
  printf '%s\n' 'Query replay reset must render Kafka CSV rows.' >&2
  exit 1
}
[[ "$(wc -l <"$temporary_directory/reset.csv")" -eq 15 ]] || {
  printf '%s\n' 'Query replay reset must retain all 15 partitions.' >&2
  exit 1
}

# Observe the reset command and its uploaded input at the Kubernetes command interface.
(
  evidence_dir="$temporary_directory"
  calls="$temporary_directory/reset.calls"
  kafka_pod() { printf 'kafka-0\n'; }
  kns() {
    printf '%s\n' "$*" >>"$calls"
    if [[ "$*" == *' sh -c '* ]]; then
      cat >"$temporary_directory/uploaded.csv"
    fi
  }
  reset_query_consumer_group query-service-matching-events matching.events \
    "$temporary_directory/boundary.json" || exit 1
  cmp "$temporary_directory/reset.csv" "$temporary_directory/uploaded.csv" || exit 1
  grep -Fq -- '--reset-offsets --from-file /tmp/query-service-matching-events-offsets.txt --execute' \
    "$calls" || exit 1
  if grep -Fq -- '--to-earliest' "$calls"; then exit 1; fi
  : >"$calls"
  if reset_query_consumer_group query-service-matching-events matching.events \
      "$temporary_directory/missing.json"; then
    exit 1
  fi
  [[ ! -s "$calls" ]]
) || {
  printf '%s\n' 'Query replay must reset from the captured boundary and reject missing evidence.' >&2
  exit 1
}

# Keep producer behavior here. Java owns the separate verdict decision.
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
source "$script_dir/../lib/active-liveness.sh"
source "$script_dir/../../critical-consumers/lib/cluster-data.sh"
fixture_dir="$temporary_directory/producers"
mkdir -p "$fixture_dir"
fixture="$repo_root/tools/query-evidence-verifier/src/test/resources/evidence-template.json"
while IFS= read -r name; do
  jq --arg name "$name" '.[$name]' "$fixture" >"$fixture_dir/$name.json"
done < <(jq -r 'keys[]' "$fixture")
cp "$fixture_dir/critical-before.json" "$fixture_dir/critical-after.json"
die() { printf '%s\n' "$*" >&2; exit 1; }
matching_ready_replicas() { printf '15\n'; }
evidence_dir="$fixture_dir"
active_liveness_evidence_dir="$fixture_dir/active-liveness"
active_liveness_started_epoch_ms=1000
timeout_seconds=30
mkdir -p "$active_liveness_evidence_dir/fix" "$active_liveness_evidence_dir/observer" "$evidence_dir/fix"
while IFS='|' read -r property path; do
  jq ".$property" "$fixture_dir/active-processing-liveness.json" >"$active_liveness_evidence_dir/$path"
done <<'INPUTS'
gatewayOpen|gateway-open-response.json
riskAdmission|risk-admission.json
matchingEvent|observer/matching-event-observation.json
observerVerdict|observer/matching-event-observer-verdict.json
orderProjection|order-projection.json
accountReservation|account-reservation.json
marketData|market-data-progress.json
fixDeliveryIntent|fix-intent.json
consumerState|consumer-state.json
exactInboxCounts|exact-inbox-counts.json
INPUTS
jq '.fixSubmission | .terminalExecType = "" | .terminalOrdStatus = ""' \
  "$fixture_dir/active-processing-liveness.json" >"$active_liveness_evidence_dir/fix/submit.json"
jq '.fixSubmission | .execId = "TERMINAL-CANCEL" | .execType = "4" | .ordStatus = "4"' \
  "$fixture_dir/active-processing-liveness.json" >"$evidence_dir/fix/completed.json"
cp "$evidence_dir/fix/completed.json" "$fixture_dir/completion-valid.json"
for mutation in '.execId = "OTHER-EXECUTION"' '.clOrdId = "OTHER-ORDER"' \
  '.accountId = "OTHER-ACCOUNT"' '.timeInForce = "0"' 'del(.terminalExecType)'; do
  jq "$mutation" "$fixture_dir/completion-valid.json" >"$evidence_dir/fix/completed.json"
  if (write_query_active_liveness_evidence 1600) >/dev/null 2>&1; then
    die "Active evidence accepted unrelated or incomplete FIX completion: $mutation"
  fi
done
cp "$fixture_dir/completion-valid.json" "$evidence_dir/fix/completed.json"
write_query_active_liveness_evidence 1600
jq -e '.fixSubmission.execType == "A" and .fixSubmission.ordStatus == "A"
  and .fixSubmission.terminalExecId == "TERMINAL-CANCEL"' \
  "$fixture_dir/active-processing-liveness.json" >/dev/null

write_query_restoration_evidence
jq -e '.criticalConsumersReady == true and .matchingReady == 15' \
  "$fixture_dir/restoration.json" >/dev/null
matching_ready_replicas() { printf '15\n'; return 1; }
if write_query_restoration_evidence; then die 'A failed readiness observation cannot prove restoration.'; fi
matching_ready_replicas() { printf '15\n'; }
cp "$fixture_dir/critical-after.json" "$fixture_dir/critical-after-valid.json"
for mutation in 'del(.quickfixQuarantines)' '.quickfixPendingIntents = 1' \
  '.marketDataProgress[0].recovery_state = "REBUILDING"'; do
  jq "$mutation" "$fixture_dir/critical-after-valid.json" >"$fixture_dir/critical-after.json"
  write_query_restoration_evidence
  jq -e '.criticalConsumersReady == false' "$fixture_dir/restoration.json" >/dev/null
done

printf 'Query-service evidence collector and producer contracts are valid.\n'

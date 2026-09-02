#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/matching-status.sh
source "$script_dir/../lib/matching-status.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/system-observation.sh
source "$script_dir/../lib/system-observation.sh"

fail() {
  printf 'System observation contract: %s\n' "$*" >&2
  exit 1
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
evidence_dir="$tmp/evidence"
mkdir -p "$evidence_dir/baseline"

[[ "$(minimum_epoch_millis 12000 15000 10000 13000)" == 10000 ]] ||
  fail 'combined observation time must use the oldest supporting fact'
epoch_millis_is_fresh 10000 13000 3500 ||
  fail 'fresh source observation should preserve submission margin'
if epoch_millis_is_fresh 10000 13501 3500; then
  fail 'source observation older than the submission budget must be retried'
fi

bundle_dir="$tmp/source-bundle"
ordering_log="$bundle_dir/order.log"
mkdir -p "$bundle_dir/matching"

capture_kafka_matching_committed_positions() {
  printf '%s\n' 'durable:matching-committed:start' >>"$ordering_log"
  sleep 0.05
  printf '{"topic":"matching.commands","partitions":[]}\n' >"$1"
  printf '%s\n' 'durable:matching-committed:end' >>"$ordering_log"
}
capture_consumer_state() {
  printf '%s\n' 'durable:consumer-progress:start' >>"$ordering_log"
  printf '{"persistenceProgress":[],"accountProgress":[],"quickfixProgress":[]}\n' >"$1"
  printf '%s\n' 'durable:consumer-progress:end' >>"$ordering_log"
}
capture_required_workloads() {
  printf '%s\n' 'durable:workloads:start' >>"$ordering_log"
  printf '{"items":[]}\n' >"$1"
  printf '%s\n' 'durable:workloads:end' >>"$ordering_log"
}
capture_matching_samples_parallel() {
  printf '%s\n' 'runtime:start' >>"$ordering_log"
  mkdir -p "$1"
  printf 'captured\n' >"$1/contract-marker"
  printf '%s\n' 'runtime:end' >>"$ordering_log"
}
capture_kafka_log_end_positions() {
  local commands_destination="$1"
  local events_destination="$2"
  local phase=closing
  [[ "$commands_destination" == *-before.json ]] && phase=opening
  printf '%s\n' "$phase:kafka" >>"$ordering_log"
  printf '{"topic":"matching.commands","partitions":[]}\n' >"$commands_destination"
  printf '{"topic":"matching.events","partitions":[]}\n' >"$events_destination"
}

date +%s%3N >"$bundle_dir/attempt-started-at"
capture_stable_observation_sources "$bundle_dir" ||
  fail 'stable observation source capture must complete all three phases'
date +%s%3N >"$bundle_dir/validation-started-at"
date +%s%3N >"$bundle_dir/validation-completed-at"
date +%s%3N >"$bundle_dir/attempt-completed-at"
matching_runtime_default_max_age_millis=5000
write_observation_timing "$bundle_dir"

for completion_file in \
  matching-commands-opening-completed-at \
  matching-events-opening-completed-at \
  matching-committed-observed-at \
  consumer-observed-at \
  workloads-observed-at \
  matching-samples-completed-at \
  matching-commands-observed-at \
  matching-events-observed-at; do
  read_capture_completion_time "$bundle_dir/$completion_file" >/dev/null ||
    fail "source completion time is missing: $completion_file"
done

[[ -f "$bundle_dir/matching-commands-before.json" &&
   -f "$bundle_dir/matching-events-before.json" ]] ||
  fail 'opening Kafka snapshots must be retained'
[[ -f "$bundle_dir/matching-commands-after.json" &&
   -f "$bundle_dir/matching-events-after.json" ]] ||
  fail 'closing Kafka snapshots must be retained'
[[ -f "$bundle_dir/matching-committed-offsets.json" &&
   -f "$bundle_dir/consumer-state.json" ]] ||
  fail 'middle observations must retain durable consumer positions'
[[ -f "$bundle_dir/workloads.json" &&
   -f "$bundle_dir/matching/contract-marker" ]] ||
  fail 'middle observations must retain workload and Matching runtime evidence'

opening_line="$(grep -n '^opening:kafka$' "$ordering_log" | cut -d: -f1)"
first_durable_line="$(grep -n '^durable:.*:start$' "$ordering_log" | head -1 | cut -d: -f1)"
last_durable_line="$(grep -n '^durable:.*:end$' "$ordering_log" | tail -1 | cut -d: -f1)"
runtime_start_line="$(grep -n '^runtime:start$' "$ordering_log" | cut -d: -f1)"
runtime_end_line="$(grep -n '^runtime:end$' "$ordering_log" | cut -d: -f1)"
closing_line="$(grep -n '^closing:kafka$' "$ordering_log" | cut -d: -f1)"
(( opening_line < first_durable_line )) ||
  fail 'opening Kafka positions must precede durable middle observations'
(( last_durable_line < runtime_start_line )) ||
  fail 'freshness-sensitive Matching samples must follow durable observations'
(( runtime_end_line < closing_line )) ||
  fail 'closing Kafka positions must follow Matching runtime samples'

jq -e '
  .openingKafka.matchingCommands.completedEpochMs != null
  and .middleObservations.matchingCommitted.durationMillis >= 40
  and .middleObservations.matchingRuntime.startedEpochMs
      >= .middleObservations.matchingCommitted.completedEpochMs
  and .closingKafka.matchingCommands.startedEpochMs
      >= .middleObservations.matchingRuntime.completedEpochMs
  and .attempt.completedEpochMs != null
' "$bundle_dir/timing.json" >/dev/null ||
  fail 'timing evidence must expose the intended phase ordering'

age_dir="$tmp/age-timing"
mkdir -p "$age_dir/matching"
printf '{"updated_at_epoch_ms":10000}\n' >"$age_dir/matching/partition-0-runtime.json"
printf '12000\n' >"$age_dir/matching-samples-completed-at"
printf '13000\n' >"$age_dir/validation-started-at"
matching_runtime_default_max_age_millis=5000
write_observation_timing "$age_dir"
jq -e '
  .matchingRuntimeFreshness.oldestSourceAgeAtCaptureCompletionMillis == 2000
  and .matchingRuntimeFreshness.oldestSourceAgeAtValidationMillis == 3000
  and .matchingRuntimeFreshness.ageAddedByCollectorMillis == 1000
  and .matchingRuntimeFreshness.remainingBudgetAtValidationMillis == 500
' "$age_dir/timing.json" >/dev/null ||
  fail 'timing evidence must separate source age from collector-added age'

validate_kafka_position_stability "$bundle_dir" ||
  fail 'unchanged opening and closing Kafka positions must be accepted'
printf '{"topic":"matching.commands","partitions":[{"partition":0,"offset":1}]}\n' \
  >"$bundle_dir/matching-commands-after.json"
observation_failure_reason=""
observation_failure_classification=""
if validate_kafka_position_stability "$bundle_dir"; then
  fail 'Kafka movement inside the observation window must invalidate that attempt'
fi
[[ "$observation_failure_classification" == KAFKA_POSITION_CHANGED ]] ||
  fail 'Kafka movement must be classified as an observation race'
printf '{"topic":"matching.commands","partitions":[]}\n' \
  >"$bundle_dir/matching-commands-after.json"
validate_kafka_position_stability "$bundle_dir" ||
  fail 'a subsequent stable Kafka window must be accepted'

cat >"$tmp/events.json" <<'JSON'
{"topic":"matching.events","partitions":[
  {"partition":0,"offset":0},
  {"partition":1,"offset":3},
  {"partition":2,"offset":0},
  {"partition":3,"offset":0},
  {"partition":4,"offset":0},
  {"partition":5,"offset":0},
  {"partition":6,"offset":0},
  {"partition":7,"offset":0},
  {"partition":8,"offset":0},
  {"partition":9,"offset":0},
  {"partition":10,"offset":0},
  {"partition":11,"offset":0},
  {"partition":12,"offset":0},
  {"partition":13,"offset":0},
  {"partition":14,"offset":0}
]}
JSON
cat >"$tmp/consumers.json" <<'JSON'
{"persistenceProgress":[
  {"partition_id":1,"last_processed_offset":2}
]}
JSON
progress="$(build_consumer_progress persistenceProgress "$tmp/consumers.json" "$tmp/events.json")" ||
  fail 'consumer progress normalization rejected a valid snapshot'
jq -e '
  length == 15
  and .[0] == {partitionId:0, committedOffset:0, endOffset:0, oldestUnprocessedAge:null}
  and .[1] == {partitionId:1, committedOffset:3, endOffset:3, oldestUnprocessedAge:null}
  and all(.[2:][]; .committedOffset == 0 and .endOffset == 0)
' <<<"$progress" >/dev/null ||
  fail 'last_processed_offset must normalize to the next Kafka position with +1'
consumer_progress_is_caught_up <<<"$progress" ||
  fail 'fully caught-up consumer progress must be accepted'

cat >"$tmp/lagging-consumers.json" <<'JSON'
{"persistenceProgress":[]}
JSON
lagging_progress="$(
  build_consumer_progress persistenceProgress "$tmp/lagging-consumers.json" "$tmp/events.json"
)" || fail 'missing progress for a non-empty partition must remain representable'
jq -e '.[] | select(.partitionId == 1) | .committedOffset == null and .endOffset == 3' \
  <<<"$lagging_progress" >/dev/null ||
  fail 'non-empty partition without progress must remain visibly not caught up'
if consumer_progress_is_caught_up <<<"$lagging_progress"; then
  fail 'consumer progress that has not caught up must be retryable, not healthy'
fi

cat >"$tmp/behind-consumers.json" <<'JSON'
{"persistenceProgress":[
  {"partition_id":1,"last_processed_offset":1}
]}
JSON
behind_progress="$(
  build_consumer_progress persistenceProgress "$tmp/behind-consumers.json" "$tmp/events.json"
)" || fail 'lagging durable progress must remain representable'
if consumer_progress_is_caught_up <<<"$behind_progress"; then
  fail 'durable progress behind Kafka end must not be treated as caught up'
fi

cat >"$tmp/workloads.json" <<'JSON'
{"items":[
  {"kind":"Deployment","metadata":{"name":"risk-service"},"spec":{"replicas":2},"status":{"readyReplicas":2,"updatedReplicas":2}},
  {"kind":"Deployment","metadata":{"name":"account-service"},"spec":{"replicas":2},"status":{"readyReplicas":2,"updatedReplicas":2}},
  {"kind":"Deployment","metadata":{"name":"persistence"},"spec":{"replicas":2},"status":{"readyReplicas":2,"updatedReplicas":2}},
  {"kind":"StatefulSet","metadata":{"name":"quickfix-gateway"},"spec":{"replicas":1},"status":{"readyReplicas":1,"updatedReplicas":1}},
  {"kind":"StatefulSet","metadata":{"name":"kafka"},"spec":{"replicas":3},"status":{"readyReplicas":3,"updatedReplicas":3}}
]}
JSON
required_workloads_are_ready "$tmp/workloads.json" ||
  fail 'all required workloads should be accepted when desired and ready replicas match'
jq '(.items[] | select(.metadata.name == "risk-service") | .status.readyReplicas) = 1' \
  "$tmp/workloads.json" >"$tmp/workloads-not-ready.json"
if required_workloads_are_ready "$tmp/workloads-not-ready.json"; then
  fail 'partially ready Risk deployment must be rejected'
fi

printf '{"x":1}\n' >"$tmp/a.json"
printf '{"x":1}\n' >"$tmp/b.json"
printf '{"x":2}\n' >"$tmp/c.json"
same_json "$tmp/a.json" "$tmp/b.json" || fail 'identical stable snapshots must compare equal'
if same_json "$tmp/a.json" "$tmp/c.json"; then
  fail 'changed snapshots must not be treated as one stable observation'
fi

trading_session_id=session
artifact_id=artifact
artifact_checksum=checksum
routing_algorithm_version=algorithm
matching_image_identity=image
matching_runtime_default_max_age_millis=5000
stale_dir="$tmp/stale"
mkdir -p "$stale_dir"
printf '{"schema_version":1,"runtime_state":"READY","partition_state":"OPEN","updated_at_epoch_ms":10000}\n' \
  >"$stale_dir/partition-0-runtime.json"
observation_failure_classification=""
if build_matching_partition_statuses \
    "$stale_dir" /dev/null /dev/null 0 0 12000 14000 "$tmp/stale.ndjson"; then
  fail 'runtime evidence that expires before validation must not be accepted'
fi
[[ "$observation_failure_classification" == EVIDENCE_EXPIRED_DURING_COLLECTION ]] ||
  fail 'fresh-at-capture runtime evidence must identify collection-induced expiration'

observation_failure_classification=""
if build_matching_partition_statuses \
    "$stale_dir" /dev/null /dev/null 0 0 14000 15000 "$tmp/stale.ndjson"; then
  fail 'runtime evidence already stale at sample completion must not be accepted'
fi
[[ "$observation_failure_classification" == SOURCE_ALREADY_STALE ]] ||
  fail 'source-side staleness must remain distinct from collector-induced expiration'

attempts=0
capture_gateway_observation_once() {
  local attempt_dir="$1"
  local destination="$2"
  local expected_active_matching_orders="${3:-0}"
  attempts="$((attempts + 1))"
  mkdir -p "$attempt_dir"
  date +%s%3N >"$attempt_dir/attempt-started-at"
  [[ "$expected_active_matching_orders" == 0 ]] ||
    fail 'default observation guard must not accept active Matching orders'
  if (( attempts == 1 )); then
    set_observation_failure KAFKA_POSITION_CHANGED \
      'Kafka positions changed during observation'
    return 2
  fi
  observation_failure_reason=''
  observation_failure_classification=''
  printf '{"status":"stable"}\n' >"$destination"
}
observation_max_attempts=3
capture_gateway_observation retry "$tmp/observation.json" ||
  fail 'retryable collection race should be retried'
[[ "$attempts" == 2 ]] ||
  fail 'stable observation should be accepted on the second attempt'
jq -e '.status == "stable"' "$tmp/observation.json" >/dev/null ||
  fail 'successful retry did not preserve the observation'
jq -e '
  .exitStatus == 2
  and .retryable == true
  and .classification == "KAFKA_POSITION_CHANGED"
  and (.reason | length > 0)
' "$evidence_dir/baseline/observation-retry-attempt-1/result.json" >/dev/null ||
  fail 'retryable attempt must retain its classification and diagnostic reason'

attempts=0
capture_gateway_observation_once() {
  local attempt_dir="$1"
  attempts="$((attempts + 1))"
  mkdir -p "$attempt_dir"
  date +%s%3N >"$attempt_dir/attempt-started-at"
  set_observation_failure EVIDENCE_EXPIRED_DURING_COLLECTION \
    'fresh evidence expired during collection'
  return 2
}
observation_max_attempts=5
if capture_gateway_observation collection-expired "$tmp/never.json"; then
  fail 'repeated collector-induced expiration must not be accepted'
fi
[[ "$attempts" == 2 ]] ||
  fail 'repeated collector-induced expiration should fail after two attempts'

attempts=0
capture_gateway_observation_once() {
  local attempt_dir="$1"
  attempts="$((attempts + 1))"
  mkdir -p "$attempt_dir"
  date +%s%3N >"$attempt_dir/attempt-started-at"
  set_observation_failure INVALID_EVIDENCE 'invalid source identity'
  return 1
}
if capture_gateway_observation fatal "$tmp/never.json"; then
  fail 'semantic failure must not be accepted'
fi
[[ "$attempts" == 1 ]] ||
  fail 'semantic failure must not be retried as a timing race'
jq -e '
  .exitStatus == 1
  and .retryable == false
  and .classification == "INVALID_EVIDENCE"
  and .reason == "invalid source identity"
' "$evidence_dir/baseline/observation-fatal-attempt-1/result.json" >/dev/null ||
  fail 'semantic failure must retain its diagnostic classification and reason'

expected_active_matching_orders_seen=missing
capture_gateway_observation_once() {
  local attempt_dir="$1"
  local destination="$2"
  expected_active_matching_orders_seen="${3:-missing}"
  mkdir -p "$attempt_dir"
  date +%s%3N >"$attempt_dir/attempt-started-at"
  printf '{"status":"expected-active"}\n' >"$destination"
}
observation_max_attempts=1
capture_gateway_observation expected-active "$tmp/expected-active.json" 1 ||
  fail 'expected active Matching order count must reach the observation collector'
[[ "$expected_active_matching_orders_seen" == 1 ]] ||
  fail 'expected active Matching order count was not forwarded'

cat >"$tmp/stale-response.json" <<'JSON'
{"openEligible":false,"reasons":["MATCHING_PARTITION_0_STATUS_STALE","RISK_STATUS_STALE"]}
JSON
cat >"$tmp/non-stale-response.json" <<'JSON'
{"openEligible":false,"reasons":["MATCHING_PARTITION_0_NOT_READY"]}
JSON
gateway_response_is_retryable_stale "$tmp/stale-response.json" ||
  fail 'stale-only Gateway rejection should be retryable'
if gateway_response_is_retryable_stale "$tmp/non-stale-response.json"; then
  fail 'non-stale Gateway rejection must not be hidden by retry logic'
fi

printf '%s\n' 'System observation semantic contracts are valid.'

#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/matching-status.sh
source "$script_dir/../lib/matching-status.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/system-observation.sh
source "$script_dir/../lib/system-observation.sh"
baseline_dir="$script_dir/baselines/system-observation"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
mkdir -p "$repo_root/out/contracts"
output_dir="$(mktemp -d "$repo_root/out/contracts/system-observation.XXXXXX")"

fail() {
  printf 'System observation contract: %s\n' "$*" >&2
  printf 'Review outputs: %s\n' "$output_dir" >&2
  exit 1
}

# Canonicalize object keys only: array ordering, values, and extra fields remain contractual.
compare_json_baseline() {
  local baseline_name="$1"
  local observed_file="$2"
  local case_output="$output_dir/$selected_case"
  local report_name="${baseline_name%.json}"
  mkdir -p "$case_output"
  jq -S . "$baseline_dir/$baseline_name" >"$case_output/$report_name.expected.json"
  jq -S . "$observed_file" >"$case_output/$report_name.actual.json"
  if ! diff -u \
      "$case_output/$report_name.expected.json" \
      "$case_output/$report_name.actual.json" >"$case_output/$report_name.diff"; then
    cat "$case_output/$report_name.diff"
    fail "baseline mismatch: $selected_case/$baseline_name"
  fi
}

setup_case() {
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  evidence_dir="$tmp/evidence"
  mkdir -p "$evidence_dir/baseline"
}

freshness_boundaries() (
  setup_case
  [[ "$(minimum_epoch_millis 12000 15000 10000 13000)" == 10000 ]] ||
    fail 'combined observation time must use the oldest supporting fact'
  epoch_millis_is_fresh 10000 13000 3500 ||
    fail 'fresh source observation should preserve submission margin'
  if epoch_millis_is_fresh 10000 13501 3500; then
    fail 'source observation older than the submission budget must be retried'
  fi
)

stable_source_capture() (
  setup_case
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
  mkdir -p "$output_dir/$selected_case"
  cp "$bundle_dir/timing.json" "$bundle_dir/order.log" "$output_dir/$selected_case/"

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

  jq '{
    openingKafkaCompleted: (.openingKafka.matchingCommands.completedEpochMs != null),
    durableCaptureIncludesDelay: (.middleObservations.matchingCommitted.durationMillis >= 40),
    runtimeFollowsDurableCapture: (.middleObservations.matchingRuntime.startedEpochMs
        >= .middleObservations.matchingCommitted.completedEpochMs),
    closingKafkaFollowsRuntime: (.closingKafka.matchingCommands.startedEpochMs
        >= .middleObservations.matchingRuntime.completedEpochMs),
    attemptCompleted: (.attempt.completedEpochMs != null)
  }' "$bundle_dir/timing.json" >"$tmp/source-ordering.json"
  compare_json_baseline source-ordering.json "$tmp/source-ordering.json"
)

timing_diagnostics() (
  setup_case
  age_dir="$tmp/age-timing"
  mkdir -p "$age_dir/matching"
  printf '{"updated_at_epoch_ms":10000}\n' >"$age_dir/matching/partition-0-runtime.json"
  printf '12000\n' >"$age_dir/matching-samples-completed-at"
  printf '13000\n' >"$age_dir/validation-started-at"
  matching_runtime_default_max_age_millis=5000
  write_observation_timing "$age_dir"
  jq '.matchingRuntimeFreshness | {
    oldestSourceAgeAtCaptureCompletionMillis,
    oldestSourceAgeAtValidationMillis,
    ageAddedByCollectorMillis,
    remainingBudgetAtValidationMillis
  }' "$age_dir/timing.json" >"$tmp/timing-diagnostics.json"
  compare_json_baseline timing-diagnostics.json "$tmp/timing-diagnostics.json"
)

kafka_snapshot_stability() (
  setup_case
  bundle_dir="$tmp/source-bundle"
  mkdir -p "$bundle_dir"
  for phase in before after; do
    printf '{"topic":"matching.commands","partitions":[]}\n' >"$bundle_dir/matching-commands-$phase.json"
    printf '{"topic":"matching.events","partitions":[]}\n' >"$bundle_dir/matching-events-$phase.json"
  done
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
)

consumer_progress() (
  setup_case
  build_consumer_progress persistenceProgress \
    "$baseline_dir/consumers.json" "$baseline_dir/events.json" >"$tmp/caught-up.json"
  compare_json_baseline caught-up-progress.json "$tmp/caught-up.json"
  consumer_progress_is_caught_up <"$tmp/caught-up.json" ||
    fail 'fully caught-up consumer progress must be accepted'

  build_consumer_progress persistenceProgress \
    "$baseline_dir/lagging-consumers.json" "$baseline_dir/events.json" >"$tmp/missing-progress.json"
  compare_json_baseline missing-progress.json "$tmp/missing-progress.json"
  if consumer_progress_is_caught_up <"$tmp/missing-progress.json"; then
    fail 'missing progress must not be treated as caught up'
  fi

  build_consumer_progress persistenceProgress \
    "$baseline_dir/behind-consumers.json" "$baseline_dir/events.json" >"$tmp/behind-progress.json"
  compare_json_baseline behind-progress.json "$tmp/behind-progress.json"
  if consumer_progress_is_caught_up <"$tmp/behind-progress.json"; then
    fail 'durable progress behind Kafka end must not be treated as caught up'
  fi
)

workload_readiness() (
  setup_case
  cp "$baseline_dir/workloads.json" "$tmp/workloads.json"
  required_workloads_are_ready "$tmp/workloads.json" ||
    fail 'all required workloads should be accepted when desired and ready replicas match'
  jq '(.items[] | select(.metadata.name == "risk-service") | .status.readyReplicas) = 1' \
    "$tmp/workloads.json" >"$tmp/workloads-not-ready.json"
  if required_workloads_are_ready "$tmp/workloads-not-ready.json"; then
    fail 'partially ready Risk deployment must be rejected'
  fi
)

json_comparison() (
  setup_case
  printf '{"x":1}\n' >"$tmp/a.json"
  printf '{"x":1}\n' >"$tmp/b.json"
  printf '{"x":2}\n' >"$tmp/c.json"
  same_json "$tmp/a.json" "$tmp/b.json" || fail 'identical stable snapshots must compare equal'
  if same_json "$tmp/a.json" "$tmp/c.json"; then
    fail 'changed snapshots must not be treated as one stable observation'
  fi
)

matching_freshness() (
  setup_case
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
)

json_encoding() (
  setup_case
  sources="$baseline_dir/normalized-sources.json"
  observation="$tmp/observation.json"
  encode_gateway_observation_json "$sources" >"$observation"
  compare_json_baseline gateway-observation.json "$observation"

  jq 'del(.riskIdentity)' "$sources" >"$tmp/incomplete-sources.json"
  if encode_gateway_observation_json "$tmp/incomplete-sources.json" >"$tmp/incomplete.json" 2>/dev/null; then
    fail 'incomplete normalized sources must not produce a READY observation'
  fi
)

collector_success_path() (
  setup_case
  trading_session_id=session
  artifact_id=artifact
  artifact_checksum=checksum
  routing_algorithm_version=algorithm
  matching_image_identity=image
  matching_runtime_default_max_age_millis=5000

  # Freeze only the clock seam; preserve real UTC formatting and source-age validation.
  date() {
    if [[ "$*" == '+%s%3N' ]]; then
      printf '13000\n'
    else
      command date "$@"
    fi
  }
  # This lookup normally comes from the external Kafka observation adapter.
  offset_for_partition() {
    jq -er --argjson partition "$2" \
      '.partitions[] | select(.partition == $partition) | .offset' "$1"
  }
  capture_stable_observation_sources() {
    local attempt_dir="$1"
    local fixture="$baseline_dir/collection-inputs.json"
    local phase partition
    for phase in before after; do
      jq '.commandEnds' "$fixture" >"$attempt_dir/matching-commands-$phase.json"
      jq '.eventEnds' "$fixture" >"$attempt_dir/matching-events-$phase.json"
    done
    jq '.commits' "$fixture" >"$attempt_dir/matching-committed-offsets.json"
    jq '.consumerState' "$fixture" >"$attempt_dir/consumer-state.json"
    cp "$baseline_dir/workloads.json" "$attempt_dir/workloads.json"
    printf '10000\n' >"$attempt_dir/matching-committed-observed-at"
    printf '11000\n' >"$attempt_dir/consumer-observed-at"
    printf '12000\n' >"$attempt_dir/workloads-observed-at"
    printf '12100\n' >"$attempt_dir/matching-samples-completed-at"
    printf '12500\n' >"$attempt_dir/matching-commands-observed-at"
    printf '12600\n' >"$attempt_dir/matching-events-observed-at"
    for partition in $(seq 0 14); do
      jq -n --arg owner "observed-owner-$partition" '{metadata:{uid:$owner}}' \
        >"$attempt_dir/matching/partition-$partition-pod-before.json"
      cp "$attempt_dir/matching/partition-$partition-pod-before.json" \
        "$attempt_dir/matching/partition-$partition-pod-after.json"
      jq '.runtimeSample' "$fixture" >"$attempt_dir/matching/partition-$partition-runtime.json"
    done
  }

  capture_gateway_observation_once "$tmp/attempt" "$tmp/observation.json" ||
    fail "collector success path rejected: $observation_failure_reason"
  compare_json_baseline collected-sources.json "$tmp/attempt/normalized-sources.json"
  compare_json_baseline collected-observation.json "$tmp/observation.json"
)

retryable_collection_race() (
  setup_case
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
  compare_json_baseline stable-observation.json "$tmp/observation.json"
  compare_json_baseline retryable-attempt.json \
    "$evidence_dir/baseline/observation-retry-attempt-1/result.json"
)

repeated_collection_expiration() (
  setup_case
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
)

fatal_source_failure() (
  setup_case
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
  compare_json_baseline fatal-attempt.json \
    "$evidence_dir/baseline/observation-fatal-attempt-1/result.json"
)

expected_active_orders() (
  setup_case
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
)

stale_gateway_response() (
  setup_case
  cp "$baseline_dir/stale-response.json" "$tmp/stale-response.json"
  cp "$baseline_dir/non-stale-response.json" "$tmp/non-stale-response.json"
  gateway_response_is_retryable_stale "$tmp/stale-response.json" ||
    fail 'stale-only Gateway rejection should be retryable'
  if gateway_response_is_retryable_stale "$tmp/non-stale-response.json"; then
    fail 'non-stale Gateway rejection must not be hidden by retry logic'
  fi
)
cases=(
  freshness_boundaries
  stable_source_capture
  timing_diagnostics
  kafka_snapshot_stability
  consumer_progress
  workload_readiness
  json_comparison
  matching_freshness
  json_encoding
  collector_success_path
  retryable_collection_race
  repeated_collection_expiration
  fatal_source_failure
  expected_active_orders
  stale_gateway_response
)
selected_cases=("${cases[@]}")
if (( $# > 0 )); then
  selected_cases=("$@")
fi
for selected_case in "${selected_cases[@]}"; do
  known_case=false
  for contract_case in "${cases[@]}"; do
    [[ "$selected_case" != "$contract_case" ]] || known_case=true
  done
  [[ "$known_case" == true ]] || fail "unknown case: $selected_case"
  "$selected_case"
  printf 'PASS: %s\n' "$selected_case"
done
printf 'System observation semantic contracts are valid. Review outputs: %s\n' "$output_dir"

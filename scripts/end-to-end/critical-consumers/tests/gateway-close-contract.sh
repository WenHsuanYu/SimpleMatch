#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
runner="$script_dir/../run-gateway-close-certification.sh"
verification_module="$script_dir/../lib/gateway-close-verification.sh"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-gateway-close-contract.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT

fail() {
  printf 'Gateway close contract: %s\n' "$*" >&2
  exit 1
}

bash -n "$runner"
bash -n "$verification_module"

# shellcheck source=scripts/end-to-end/critical-consumers/run-gateway-close-certification.sh
source "$runner"
declare -F die >/dev/null ||
  fail 'close runner must provide the fail-closed callback required by shared helpers'

continued_after_die="$temporary_directory/continued-after-die"
( die 'contract failure'; : >"$continued_after_die" ) >/dev/null 2>&1 || true
[[ ! -e "$continued_after_die" ]] ||
  fail 'shared-helper die callback must terminate the current runner flow'

(
  kafka_observer_created=false
  kns() {
    return 99
  }
  delete_kafka_observer_pod
) || fail 'Kafka observer cleanup must be a no-op when this run did not create the Pod'

observer_delete_calls="$temporary_directory/observer-delete-calls"
(
  kafka_observer_created=true
  timeout_seconds=7
  kns() {
    local IFS=' '
    printf '%s\n' "$*" >>"$observer_delete_calls"
  }
  delete_kafka_observer_pod
  [[ "$kafka_observer_created" == false ]]
) || fail 'run-owned Kafka observer cleanup should complete after bounded deletion'
grep -F 'delete pod critical-consumer-kafka-observer --ignore-not-found --wait=true --timeout=7s' \
  "$observer_delete_calls" >/dev/null ||
  fail 'Kafka observer deletion must wait for the fixed-name Pod to disappear'

gateway_restore_calls="$temporary_directory/gateway-restore-calls"
(
  gateway_env_modified=true
  restoration_failed=false
  timeout_seconds=7
  kns() {
    local IFS=' '
    printf '%s\n' "$*" >>"$gateway_restore_calls"
  }
  restore_gateway_environment
  [[ "$gateway_env_modified" == false && "$restoration_failed" == false ]]
) || fail 'Gateway override cleanup should complete after a successful rollout'
grep -F 'set env statefulset/quickfix-gateway' "$gateway_restore_calls" >/dev/null ||
  fail 'Gateway cleanup must remove the temporary operations overrides'
grep -F 'rollout status statefulset/quickfix-gateway --timeout=7s' \
  "$gateway_restore_calls" >/dev/null ||
  fail 'Gateway cleanup must wait for restoration rollout before PASS publication'

baseline_failure_calls="$temporary_directory/baseline-failure-calls"
(
  timeout_seconds=7
  failure_reason=""
  kns() {
    local IFS=' '
    printf '%s\n' "$*" >>"$baseline_failure_calls"
    return 73
  }
  capture_consumer_state() {
    return 74
  }

  # A function called from an if-condition cannot rely on Bash `errexit`.
  # The phase must therefore propagate the rollout error explicitly.
  if wait_for_clean_baseline; then
    exit 75
  fi
  [[ "$failure_reason" == 'workload did not become Ready: statefulset/matching' ]]
) || fail 'baseline rollout failure must propagate even when errexit is suppressed'
[[ "$(cat "$baseline_failure_calls")" == \
   'rollout status statefulset/matching --timeout=7s' ]] ||
  fail 'baseline verification must stop at the first failed rollout'

before="$temporary_directory/commands-before.json"
after="$temporary_directory/commands-after.json"
committed="$temporary_directory/matching-committed.json"
events="$temporary_directory/events.json"
consumers="$temporary_directory/consumers.json"

jq -n '{topic:"matching.commands", partitions:[range(0;15) as $p | {partition:$p,offset:(10+$p)}]}' \
  >"$before"
jq -n '{topic:"matching.commands", partitions:[range(0;15) as $p | {partition:$p,offset:(11+$p)}]}' \
  >"$after"
close_barriers_advanced_exactly_once "$before" "$after" ||
  fail 'one Close Barrier per matching.commands partition must pass'

close_payload_evidence="$temporary_directory/close-barriers.json"
(
  trading_session_id='2026-08-27-regular'
  trading_day='2026-08-27'
  artifact_checksum='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  routing_algorithm_version='price-time-v1'
  kafka_observation_request() {
    local path="$1"
    local destination="$2"
    local request="$3"
    [[ "$path" == '/close-barriers' ]]
    jq -e '
      .tradingSessionId == "2026-08-27-regular"
      and .tradingDay == "2026-08-27"
      and .artifactContentSha256 ==
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      and .routingAlgorithmVersion == "price-time-v1"
      and (.before.partitions | length) == 15
      and (.after.partitions | length) == 15
    ' "$request" >/dev/null
    jq -n '{
      topic:"matching.commands",
      records:[range(0;15) as $p | {partition:$p,offset:(10+$p),commandId:("close-"+($p|tostring))}]
    }' >"$destination"
  }
  capture_kafka_close_barriers "$before" "$after" "$close_payload_evidence"
) || fail 'exact Close Barrier identity observation must cross the Kafka payload seam'

jq '.partitions[7].offset += 1' "$after" >"$temporary_directory/commands-too-far.json"
if close_barriers_advanced_exactly_once "$before" "$temporary_directory/commands-too-far.json"; then
  fail 'a partition advancing by more than one must fail the Close Barrier proof'
fi

jq 'del(.partitions[14])' "$after" >"$temporary_directory/commands-missing-partition.json"
if close_barriers_advanced_exactly_once "$before" "$temporary_directory/commands-missing-partition.json"; then
  fail 'a missing command partition must fail the Close Barrier proof'
fi

closed_runtime='{
  "schema_version":1,
  "runtime_state":"RUNNING",
  "partition_state":"CLOSED",
  "pending_inputs":0,
  "pending_publications":0,
  "updated_at_epoch_ms":10000
}'
matching_runtime_is_closed 14000 5000 <<<"$closed_runtime" ||
  fail 'fresh RUNNING/CLOSED runtime evidence must pass'
if matching_runtime_is_closed 16000 5000 <<<"$closed_runtime"; then
  fail 'stale CLOSED runtime evidence must fail'
fi
if matching_runtime_is_closed 14000 5000 <<<"$(jq '.runtime_state = "FAILED"' <<<"$closed_runtime")"; then
  fail 'FAILED Matching runtime must not pass merely because its partition is CLOSED'
fi
if matching_runtime_is_closed 14000 5000 <<<"$(jq '.updated_at_epoch_ms = 15000' <<<"$closed_runtime")"; then
  fail 'future-dated Matching runtime evidence must fail'
fi

jq -n '{
  topic:"matching.commands",
  partitions:[range(0;15) as $p | {partition:$p,committedOffset:(11+$p)}]
}' >"$committed"
matching_committed_covers_commands "$after" "$committed" ||
  fail 'Matching committed positions at command log end must pass'

jq '.partitions[4].committedOffset -= 1' "$committed" \
  >"$temporary_directory/matching-behind.json"
if matching_committed_covers_commands "$after" "$temporary_directory/matching-behind.json"; then
  fail 'a Matching owner behind its Close Barrier must fail the proof'
fi

jq 'del(.partitions[2])' "$committed" >"$temporary_directory/matching-missing-partition.json"
if matching_committed_covers_commands "$after" "$temporary_directory/matching-missing-partition.json"; then
  fail 'missing Matching committed evidence must fail the proof'
fi

jq -n '{
  topic:"matching.events",
  partitions:[range(0;15) as $p | {partition:$p,offset:(if $p == 3 then 5 else 0 end)}]
}' >"$events"
jq -n '{
  persistenceProgress:[{partition_id:3,last_processed_offset:4}],
  accountProgress:[{partition_id:3,last_processed_offset:4}],
  quickfixProgress:[{partition_id:3,last_processed_offset:4}],
  persistenceQuarantines:0,
  accountQuarantines:0,
  quickfixQuarantines:0,
  persistenceQuarantineHistory:0,
  accountQuarantineHistory:0,
  quickfixQuarantineHistory:0,
  quickfixPendingIntents:1,
  activeMatchingOrders:0
}' >"$consumers"
critical_consumers_cover_events "$events" "$consumers" ||
  fail 'caught-up critical consumers without quarantine history must pass'

jq '.quickfixProgress[0].last_processed_offset = 3' "$consumers" \
  >"$temporary_directory/consumer-behind.json"
if critical_consumers_cover_events "$events" "$temporary_directory/consumer-behind.json"; then
  fail 'a critical consumer behind the close event must fail the proof'
fi

jq '.accountQuarantineHistory = 1' "$consumers" \
  >"$temporary_directory/consumer-quarantine-history.json"
if critical_consumers_cover_events "$events" \
    "$temporary_directory/consumer-quarantine-history.json"; then
  fail 'any quarantine history created during close must fail the proof'
fi

grep -F 'source "$script_dir/lib/failure-support.sh"' "$runner" >/dev/null ||
  fail 'close certification must reuse the existing critical-consumer runtime'
grep -F 'source "$script_dir/lib/system-observation.sh"' "$runner" >/dev/null ||
  fail 'close certification must reuse the existing observation collector'
grep -F 'source "$script_dir/lib/gateway-close-verification.sh"' "$runner" >/dev/null ||
  fail 'close certification must isolate close-specific proof logic from orchestration'
grep -F -- '--retained-evidence-dir PATH' "$runner" >/dev/null ||
  fail 'retained production-like evidence must be an explicit runner input'
grep -F 'simplematch_certification_verifier_image' "$runner" >/dev/null ||
  fail 'retained-run provenance must be validated explicitly'
grep -F 'capture_gateway_observation' "$runner" >/dev/null ||
  fail 'close certification must use normalized Gateway observations'
grep -F 'gateway_request POST /operations/close-day' "$runner" >/dev/null ||
  fail 'close certification must cross the authenticated Gateway operations seam'
grep -F 'capture_kafka_matching_commands_end_positions' "$runner" >/dev/null ||
  fail 'close certification must reuse the warm Kafka observation adapter'
grep -F 'capture_kafka_matching_committed_positions' "$verification_module" >/dev/null ||
  fail 'close verification must prove durable Matching command progress'
grep -F 'verify_close_barrier_payloads' "$runner" >/dev/null ||
  fail 'close certification must decode and verify each Close Barrier payload'
grep -F -- '--slurpfile closeBarriers "$evidence_dir/close/close-barriers.json"' \
  "$runner" >/dev/null ||
  fail 'PASS verdict must retain decoded Close Barrier payload evidence'
grep -F '.partition_state == "CLOSED"' "$verification_module" >/dev/null ||
  fail 'close verification must prove every Matching runtime reaches CLOSED'
grep -F 'capture_matching_partition_sample "$partition" "$samples_dir" false' \
  "$verification_module" >/dev/null ||
  fail 'close verification must reuse stable pod sampling without requiring post-close readiness'

running_closed_pod="$temporary_directory/running-closed-pod.json"
running_closed_metrics="$temporary_directory/running-closed-metrics.json"
jq -n '{metadata:{uid:"pod-uid"},status:{containerStatuses:[{name:"matching",ready:false,state:{running:{startedAt:"2026-08-27T00:00:00Z"}}}]}}' \
  >"$running_closed_pod"
jq -n '{schema_version:1,runtime_state:"RUNNING",partition_state:"CLOSED",pending_inputs:0,pending_publications:0,updated_at_epoch_ms:1}' \
  >"$running_closed_metrics"
(
  mkdir -p "$temporary_directory/running-closed-samples"
  kns() {
    case "$1" in
      get)
        cat "$running_closed_pod"
        ;;
      exec)
        cat "$running_closed_metrics"
        ;;
      *)
        return 99
        ;;
    esac
  }
  capture_matching_partition_sample \
    3 "$temporary_directory/running-closed-samples" false
) || fail 'close verification must accept a running pod whose readiness is false after close'
(
  mkdir -p "$temporary_directory/ready-required-samples"
  kns() {
    case "$1" in
      get)
        cat "$running_closed_pod"
        ;;
      exec)
        cat "$running_closed_metrics"
        ;;
      *)
        return 99
        ;;
    esac
  }
  capture_matching_partition_sample \
    3 "$temporary_directory/ready-required-samples" true
) && fail 'open-state sampling must still require a Ready pod'
grep -F 'require_exact_event_once "$terminal_event_id"' "$runner" >/dev/null ||
  fail 'close certification must attribute terminal processing to the selected order event'
grep -F 'capture_kafka_matching_events_end_positions' "$runner" >/dev/null ||
  fail 'close certification must use one post-close event-log snapshot'
grep -F 'wait_critical_consumers_to_close' "$runner" >/dev/null ||
  fail 'close certification must prove critical-consumer durable progress'
grep -F 'delete_kafka_observer_pod || restoration_failed=true' "$runner" >/dev/null ||
  fail 'cleanup must delete only a Kafka observer owned by the current run'
if grep -F 'events-before.json' "$runner" "$verification_module" >/dev/null; then
  fail 'close certification must not use a redundant pre-close event movement probe'
fi

initialize_line="$(grep -n '^  initialize_evidence || return 1$' "$runner" | cut -d: -f1)"
provenance_line="$(grep -n '^  validate_retained_run || return 1$' "$runner" | cut -d: -f1)"
[[ "$initialize_line" =~ ^[0-9]+$ && "$provenance_line" =~ ^[0-9]+$ ]] ||
  fail 'main must expose explicit evidence and provenance phases'
(( initialize_line < provenance_line )) ||
  fail 'evidence must be initialized before provenance checks so preflight failure gets a verdict'

printf 'Gateway close certification contracts are valid.\n'

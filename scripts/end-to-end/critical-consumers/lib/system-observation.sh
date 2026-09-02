#!/usr/bin/env bash

# Collects one stable TradingSystemObservation from deployed sources.
# Matching status semantics remain in matching-status.sh; environment I/O comes
# from failure-support.sh.

observation_submission_margin_millis=1500
observation_max_attempts=5
observation_failure_reason=""
observation_failure_classification=""

set_observation_failure() {
  observation_failure_classification="$1"
  observation_failure_reason="$2"
}

identity_json() {
  jq -n \
    --arg tradingSessionId "$trading_session_id" \
    --arg artifactId "$artifact_id" \
    --arg artifactContentSha256 "$artifact_checksum" \
    --arg matchingAlgorithmVersion "$routing_algorithm_version" \
    --arg matchingImageIdentity "$matching_image_identity" \
    '{
      tradingSessionId:$tradingSessionId,
      artifactId:$artifactId,
      artifactContentSha256:$artifactContentSha256,
      commandSchemaVersion:1,
      eventSchemaVersion:1,
      matchingAlgorithmVersion:$matchingAlgorithmVersion,
      matchingImageIdentity:$matchingImageIdentity
    }'
}

iso_utc_from_epoch_millis() {
  local epoch_millis="$1"
  local seconds="$((epoch_millis / 1000))"
  local millis="$((epoch_millis % 1000))"
  date -u -d "@${seconds}.$(printf '%03d' "$millis")" '+%Y-%m-%dT%H:%M:%S.%3NZ'
}

minimum_epoch_millis() {
  local minimum="$1"
  shift
  local value
  for value in "$@"; do
    (( value < minimum )) && minimum="$value"
  done
  printf '%s\n' "$minimum"
}

epoch_millis_is_fresh() {
  local observed_epoch_millis="$1"
  local now_epoch_millis="$2"
  local maximum_age_millis="$3"
  [[ "$observed_epoch_millis" =~ ^[0-9]+$ ]] || return 1
  [[ "$now_epoch_millis" =~ ^[0-9]+$ ]] || return 1
  [[ "$maximum_age_millis" =~ ^[0-9]+$ ]] || return 1
  (( observed_epoch_millis <= now_epoch_millis )) || return 1
  (( now_epoch_millis - observed_epoch_millis <= maximum_age_millis ))
}

timing_epoch_or_null() {
  local path="$1"
  local value
  value="$(cat "$path" 2>/dev/null || true)"
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "$value"
  else
    printf 'null\n'
  fi
}

matching_runtime_extreme_or_null() {
  local attempt_dir="$1"
  local mode="$2"
  local -a metrics_files=()
  mapfile -t metrics_files < <(
    compgen -G "$attempt_dir/matching/partition-*-runtime.json" || true
  )
  if ((${#metrics_files[@]} == 0)); then
    printf 'null\n'
    return 0
  fi

  case "$mode" in
    min)
      jq -s '
        map(.updated_at_epoch_ms | select(type == "number") | floor)
        | if length == 0 then null else min end
      ' "${metrics_files[@]}"
      ;;
    max)
      jq -s '
        map(.updated_at_epoch_ms | select(type == "number") | floor)
        | if length == 0 then null else max end
      ' "${metrics_files[@]}"
      ;;
    *)
      return 1
      ;;
  esac
}

write_observation_timing() {
  local attempt_dir="$1"
  local attempt_started attempt_completed validation_started validation_completed
  local opening_commands_started opening_commands_completed
  local opening_events_started opening_events_completed
  local committed_started committed_completed
  local consumer_started consumer_completed
  local workloads_started workloads_completed
  local matching_started matching_completed
  local closing_commands_started closing_commands_completed
  local closing_events_started closing_events_completed
  local oldest_runtime newest_runtime maximum_fact_age_millis
  local oldest_runtime_age_at_capture_completion oldest_runtime_age_at_validation
  local age_added_by_collector remaining_freshness_budget

  attempt_started="$(timing_epoch_or_null "$attempt_dir/attempt-started-at")"
  attempt_completed="$(timing_epoch_or_null "$attempt_dir/attempt-completed-at")"
  validation_started="$(timing_epoch_or_null "$attempt_dir/validation-started-at")"
  validation_completed="$(timing_epoch_or_null "$attempt_dir/validation-completed-at")"
  opening_commands_started="$(
    timing_epoch_or_null "$attempt_dir/matching-commands-opening-started-at"
  )"
  opening_commands_completed="$(
    timing_epoch_or_null "$attempt_dir/matching-commands-opening-completed-at"
  )"
  opening_events_started="$(
    timing_epoch_or_null "$attempt_dir/matching-events-opening-started-at"
  )"
  opening_events_completed="$(
    timing_epoch_or_null "$attempt_dir/matching-events-opening-completed-at"
  )"
  committed_started="$(timing_epoch_or_null "$attempt_dir/matching-committed-started-at")"
  committed_completed="$(timing_epoch_or_null "$attempt_dir/matching-committed-observed-at")"
  consumer_started="$(timing_epoch_or_null "$attempt_dir/consumer-started-at")"
  consumer_completed="$(timing_epoch_or_null "$attempt_dir/consumer-observed-at")"
  workloads_started="$(timing_epoch_or_null "$attempt_dir/workloads-started-at")"
  workloads_completed="$(timing_epoch_or_null "$attempt_dir/workloads-observed-at")"
  matching_started="$(timing_epoch_or_null "$attempt_dir/matching-samples-started-at")"
  matching_completed="$(timing_epoch_or_null "$attempt_dir/matching-samples-completed-at")"
  closing_commands_started="$(
    timing_epoch_or_null "$attempt_dir/matching-commands-closing-started-at"
  )"
  closing_commands_completed="$(
    timing_epoch_or_null "$attempt_dir/matching-commands-observed-at"
  )"
  closing_events_started="$(
    timing_epoch_or_null "$attempt_dir/matching-events-closing-started-at"
  )"
  closing_events_completed="$(
    timing_epoch_or_null "$attempt_dir/matching-events-observed-at"
  )"
  oldest_runtime="$(matching_runtime_extreme_or_null "$attempt_dir" min)"
  newest_runtime="$(matching_runtime_extreme_or_null "$attempt_dir" max)"

  maximum_fact_age_millis=null
  if [[ "${matching_runtime_default_max_age_millis:-}" =~ ^[0-9]+$ ]] &&
     (( matching_runtime_default_max_age_millis > observation_submission_margin_millis )); then
    maximum_fact_age_millis="$((
      matching_runtime_default_max_age_millis - observation_submission_margin_millis
    ))"
  fi

  oldest_runtime_age_at_capture_completion=null
  oldest_runtime_age_at_validation=null
  age_added_by_collector=null
  remaining_freshness_budget=null
  if [[ "$oldest_runtime" =~ ^[0-9]+$ && "$matching_completed" =~ ^[0-9]+$ ]]; then
    oldest_runtime_age_at_capture_completion="$((matching_completed - oldest_runtime))"
  fi
  if [[ "$oldest_runtime" =~ ^[0-9]+$ && "$validation_started" =~ ^[0-9]+$ ]]; then
    oldest_runtime_age_at_validation="$((validation_started - oldest_runtime))"
    if [[ "$matching_completed" =~ ^[0-9]+$ ]]; then
      age_added_by_collector="$((validation_started - matching_completed))"
    fi
    if [[ "$maximum_fact_age_millis" =~ ^[0-9]+$ ]]; then
      remaining_freshness_budget="$((
        maximum_fact_age_millis - oldest_runtime_age_at_validation
      ))"
    fi
  fi

  jq -n \
    --argjson attemptStarted "$attempt_started" \
    --argjson attemptCompleted "$attempt_completed" \
    --argjson openingCommandsStarted "$opening_commands_started" \
    --argjson openingCommandsCompleted "$opening_commands_completed" \
    --argjson openingEventsStarted "$opening_events_started" \
    --argjson openingEventsCompleted "$opening_events_completed" \
    --argjson committedStarted "$committed_started" \
    --argjson committedCompleted "$committed_completed" \
    --argjson consumerStarted "$consumer_started" \
    --argjson consumerCompleted "$consumer_completed" \
    --argjson workloadsStarted "$workloads_started" \
    --argjson workloadsCompleted "$workloads_completed" \
    --argjson matchingStarted "$matching_started" \
    --argjson matchingCompleted "$matching_completed" \
    --argjson closingCommandsStarted "$closing_commands_started" \
    --argjson closingCommandsCompleted "$closing_commands_completed" \
    --argjson closingEventsStarted "$closing_events_started" \
    --argjson closingEventsCompleted "$closing_events_completed" \
    --argjson validationStarted "$validation_started" \
    --argjson validationCompleted "$validation_completed" \
    --argjson oldestRuntime "$oldest_runtime" \
    --argjson newestRuntime "$newest_runtime" \
    --argjson maximumFactAge "$maximum_fact_age_millis" \
    --argjson oldestRuntimeAgeAtCapture "$oldest_runtime_age_at_capture_completion" \
    --argjson oldestRuntimeAgeAtValidation "$oldest_runtime_age_at_validation" \
    --argjson ageAddedByCollector "$age_added_by_collector" \
    --argjson remainingFreshnessBudget "$remaining_freshness_budget" '
      def phase($started; $completed):
        {
          startedEpochMs:$started,
          completedEpochMs:$completed,
          durationMillis:(
            if ($started != null and $completed != null)
            then $completed - $started
            else null
            end
          )
        };
      {
        attempt:phase($attemptStarted; $attemptCompleted),
        openingKafka:{
          matchingCommands:phase($openingCommandsStarted; $openingCommandsCompleted),
          matchingEvents:phase($openingEventsStarted; $openingEventsCompleted)
        },
        middleObservations:{
          matchingCommitted:phase($committedStarted; $committedCompleted),
          consumerProgress:phase($consumerStarted; $consumerCompleted),
          workloads:phase($workloadsStarted; $workloadsCompleted),
          matchingRuntime:phase($matchingStarted; $matchingCompleted)
        },
        closingKafka:{
          matchingCommands:phase($closingCommandsStarted; $closingCommandsCompleted),
          matchingEvents:phase($closingEventsStarted; $closingEventsCompleted)
        },
        validation:phase($validationStarted; $validationCompleted),
        matchingRuntimeFreshness:{
          oldestSourceEpochMs:$oldestRuntime,
          newestSourceEpochMs:$newestRuntime,
          maximumFactAgeMillis:$maximumFactAge,
          oldestSourceAgeAtCaptureCompletionMillis:$oldestRuntimeAgeAtCapture,
          oldestSourceAgeAtValidationMillis:$oldestRuntimeAgeAtValidation,
          ageAddedByCollectorMillis:$ageAddedByCollector,
          remainingBudgetAtValidationMillis:$remainingFreshnessBudget
        }
      }
    ' >"$attempt_dir/timing.json"
}

capture_required_workloads() {
  local destination="$1"
  kns get \
    deployment/risk-service \
    deployment/account-service \
    deployment/persistence \
    statefulset/quickfix-gateway \
    statefulset/kafka \
    -o json >"$destination"
}

required_workloads_are_ready() {
  local snapshot="$1"
  jq -e '
    def fully_ready($kind; $name):
      [.items[] | select(.kind == $kind and .metadata.name == $name)] as $matches
      | ($matches | length) == 1
      and (($matches[0].spec.replicas // 0) > 0)
      and (($matches[0].status.readyReplicas // 0) == $matches[0].spec.replicas)
      and (($matches[0].status.updatedReplicas // 0) == $matches[0].spec.replicas);
    fully_ready("Deployment"; "risk-service")
    and fully_ready("Deployment"; "account-service")
    and fully_ready("Deployment"; "persistence")
    and fully_ready("StatefulSet"; "quickfix-gateway")
    and fully_ready("StatefulSet"; "kafka")
    and ([.items[] | select(.kind == "StatefulSet" and .metadata.name == "quickfix-gateway")][0].spec.replicas == 1)
    and ([.items[] | select(.kind == "StatefulSet" and .metadata.name == "kafka")][0].spec.replicas == 3)
  ' "$snapshot" >/dev/null
}

capture_matching_partition_sample() {
  local partition="$1"
  local samples_dir="$2"
  local require_ready="${3:-true}"
  local pod="matching-$partition"
  local before="$samples_dir/partition-$partition-pod-before.json"
  local after="$samples_dir/partition-$partition-pod-after.json"
  local metrics="$samples_dir/partition-$partition-runtime.json"

  [[ "$require_ready" == true || "$require_ready" == false ]] || return 1

  kns get pod "$pod" -o json >"$before" || return 1
  if [[ "$require_ready" == true ]]; then
    jq -e '
      [.status.containerStatuses[]? | select(.name == "matching")] as $containers
      | ($containers | length) == 1 and $containers[0].ready == true
    ' "$before" >/dev/null || return 1
  else
    jq -e '
      [.status.containerStatuses[]? | select(.name == "matching")] as $containers
      | ($containers | length) == 1 and ($containers[0].state.running | type) == "object"
    ' "$before" >/dev/null || return 1
  fi

  kns exec "$pod" -c matching -- \
    cat /var/lib/simplematch/matching/runtime-metrics.json >"$metrics" || return 1
  jq -e 'type == "object"' "$metrics" >/dev/null || return 1

  kns get pod "$pod" -o json >"$after" || return 1
  if [[ "$require_ready" == true ]]; then
    jq -e '
      [.status.containerStatuses[]? | select(.name == "matching")] as $containers
      | ($containers | length) == 1 and $containers[0].ready == true
    ' "$after" >/dev/null || return 1
  else
    jq -e '
      [.status.containerStatuses[]? | select(.name == "matching")] as $containers
      | ($containers | length) == 1 and ($containers[0].state.running | type) == "object"
    ' "$after" >/dev/null || return 1
  fi

  local before_uid after_uid
  before_uid="$(jq -r '.metadata.uid // empty' "$before")"
  after_uid="$(jq -r '.metadata.uid // empty' "$after")"
  [[ -n "$before_uid" && "$before_uid" == "$after_uid" ]]
}

capture_matching_samples_parallel() {
  local samples_dir="$1"
  mkdir -p "$samples_dir"
  local -a pids=()
  local partition
  local failed=false

  for partition in $(seq 0 14); do
    capture_matching_partition_sample "$partition" "$samples_dir" &
    pids+=("$!")
  done

  local pid
  for pid in "${pids[@]}"; do
    wait "$pid" || failed=true
  done
  [[ "$failed" == false ]]
}

capture_source_with_timing() {
  local started_file="$1"
  local completed_file="$2"
  shift 2

  date +%s%3N >"$started_file"
  local status=0
  "$@" || status="$?"
  date +%s%3N >"$completed_file"
  return "$status"
}

capture_opening_kafka_positions() {
  local attempt_dir="$1"
  local started completed status=0
  started="$(date +%s%3N)"
  printf '%s\n' "$started" >"$attempt_dir/matching-commands-opening-started-at"
  printf '%s\n' "$started" >"$attempt_dir/matching-events-opening-started-at"

  capture_kafka_log_end_positions \
    "$attempt_dir/matching-commands-before.json" \
    "$attempt_dir/matching-events-before.json" || status="$?"

  completed="$(date +%s%3N)"
  printf '%s\n' "$completed" >"$attempt_dir/matching-commands-opening-completed-at"
  printf '%s\n' "$completed" >"$attempt_dir/matching-events-opening-completed-at"
  if (( status != 0 )); then
    set_observation_failure SOURCE_COLLECTION_FAILED \
      "cannot capture opening Kafka log-end positions"
    return 1
  fi
}

capture_middle_observation_sources() {
  local attempt_dir="$1"
  local -a pids=()
  local -a failure_reasons=()

  capture_source_with_timing \
    "$attempt_dir/matching-committed-started-at" \
    "$attempt_dir/matching-committed-observed-at" \
    capture_kafka_matching_committed_positions \
    "$attempt_dir/matching-committed-offsets.json" &
  pids+=("$!")
  failure_reasons+=("Matching committed positions are temporarily unavailable")

  capture_source_with_timing \
    "$attempt_dir/consumer-started-at" \
    "$attempt_dir/consumer-observed-at" \
    capture_consumer_state "$attempt_dir/consumer-state.json" &
  pids+=("$!")
  failure_reasons+=("critical-consumer durable progress is temporarily unavailable")

  capture_source_with_timing \
    "$attempt_dir/workloads-started-at" \
    "$attempt_dir/workloads-observed-at" \
    capture_required_workloads "$attempt_dir/workloads.json" &
  pids+=("$!")
  failure_reasons+=("required Kubernetes workload status is temporarily unavailable")

  local index
  local failed=false
  for index in "${!pids[@]}"; do
    if ! wait "${pids[$index]}"; then
      if [[ "$failed" == false ]]; then
        set_observation_failure SOURCE_COLLECTION_FAILED "${failure_reasons[$index]}"
      fi
      failed=true
    fi
  done
  [[ "$failed" == false ]] || return 1

  capture_source_with_timing \
    "$attempt_dir/matching-samples-started-at" \
    "$attempt_dir/matching-samples-completed-at" \
    capture_matching_samples_parallel "$attempt_dir/matching" || {
    set_observation_failure SOURCE_COLLECTION_FAILED \
      "one or more Matching Pod samples changed or were unavailable"
    return 1
  }
}

capture_closing_kafka_positions() {
  local attempt_dir="$1"
  local started completed status=0
  started="$(date +%s%3N)"
  printf '%s\n' "$started" >"$attempt_dir/matching-commands-closing-started-at"
  printf '%s\n' "$started" >"$attempt_dir/matching-events-closing-started-at"

  capture_kafka_log_end_positions \
    "$attempt_dir/matching-commands-after.json" \
    "$attempt_dir/matching-events-after.json" || status="$?"

  completed="$(date +%s%3N)"
  printf '%s\n' "$completed" >"$attempt_dir/matching-commands-observed-at"
  printf '%s\n' "$completed" >"$attempt_dir/matching-events-observed-at"
  if (( status != 0 )); then
    set_observation_failure SOURCE_COLLECTION_FAILED \
      "cannot capture closing Kafka log-end positions"
    return 1
  fi
}

capture_stable_observation_sources() {
  local attempt_dir="$1"
  mkdir -p "$attempt_dir/matching"

  capture_opening_kafka_positions "$attempt_dir" || return 1
  capture_middle_observation_sources "$attempt_dir" || return 1
  capture_closing_kafka_positions "$attempt_dir" || return 1
}

read_capture_completion_time() {
  local completion_file="$1"
  local value
  value="$(cat "$completion_file" 2>/dev/null || true)"
  [[ "$value" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$value"
}

build_matching_partition_statuses() {
  local samples_dir="$1"
  local command_offsets="$2"
  local committed_offsets="$3"
  local committed_observed_epoch_millis="$4"
  local command_observed_epoch_millis="$5"
  local matching_samples_completed_epoch_millis="$6"
  local validation_epoch_millis="$7"
  local destination="$8"
  local identity
  identity="$(identity_json)" || {
    set_observation_failure INVALID_EVIDENCE "cannot build Matching identity"
    return 1
  }
  : >"$destination"

  local maximum_fact_age_millis
  (( matching_runtime_default_max_age_millis > observation_submission_margin_millis )) || {
    set_observation_failure INVALID_CONFIGURATION \
      "observation submission margin exceeds Gateway freshness threshold"
    return 1
  }
  maximum_fact_age_millis="$((matching_runtime_default_max_age_millis - observation_submission_margin_millis))"

  local partition
  for partition in $(seq 0 14); do
    local before="$samples_dir/partition-$partition-pod-before.json"
    local after="$samples_dir/partition-$partition-pod-after.json"
    local metrics="$samples_dir/partition-$partition-runtime.json"
    local owner_id committed_offset end_offset
    local runtime_epoch_millis observed_epoch_millis observed_at readiness_reason

    runtime_epoch_millis="$(
      jq -er '.updated_at_epoch_ms | select(type == "number") | floor' "$metrics"
    )" || {
      set_observation_failure INVALID_EVIDENCE \
        "Matching partition $partition source timestamp is invalid"
      return 1
    }

    readiness_reason="$(
      matching_runtime_readiness_reason \
        "$validation_epoch_millis" \
        "$maximum_fact_age_millis" <"$metrics"
    )" || {
      set_observation_failure INVALID_EVIDENCE \
        "Matching partition $partition runtime evidence is invalid"
      return 1
    }
    case "$readiness_reason" in
      READY) ;;
      STATUS_STALE)
        if epoch_millis_is_fresh \
            "$runtime_epoch_millis" \
            "$matching_samples_completed_epoch_millis" \
            "$maximum_fact_age_millis"; then
          set_observation_failure EVIDENCE_EXPIRED_DURING_COLLECTION \
            "Matching partition $partition was fresh when sampled but expired before validation"
        else
          set_observation_failure SOURCE_ALREADY_STALE \
            "Matching partition $partition was already stale when sampling completed"
        fi
        return 2
        ;;
      RUNTIME_NOT_READY|PARTITION_NOT_OPEN)
        set_observation_failure MATCHING_NOT_READY \
          "Matching partition $partition is not yet ready: $readiness_reason"
        return 2
        ;;
      *)
        printf '%s\n' "$readiness_reason" \
          >"$samples_dir/partition-$partition-readiness-failure.txt"
        set_observation_failure INVALID_EVIDENCE \
          "Matching partition $partition evidence failed: $readiness_reason"
        return 1
        ;;
    esac

    owner_id="$(jq -er '.metadata.uid' "$after")" || {
      set_observation_failure INVALID_EVIDENCE \
        "Matching partition $partition Pod UID is missing"
      return 1
    }
    [[ "$owner_id" == "$(jq -er '.metadata.uid' "$before")" ]] || {
      set_observation_failure MATCHING_SAMPLE_CHANGED \
        "Matching partition $partition Pod changed during observation"
      return 2
    }

    committed_offset="$(
      matching_committed_offset_for_partition "$committed_offsets" "$partition"
    )" || {
      set_observation_failure MATCHING_PROGRESS_INCOMPLETE \
        "Matching partition $partition committed position is unavailable"
      return 2
    }
    end_offset="$(offset_for_partition "$command_offsets" "$partition")" || {
      set_observation_failure INVALID_EVIDENCE \
        "Matching partition $partition command end offset is invalid"
      return 1
    }
    [[ "$committed_offset" == "$end_offset" ]] || {
      set_observation_failure MATCHING_PROGRESS_INCOMPLETE \
        "Matching partition $partition has not caught up with matching.commands"
      return 2
    }

    observed_epoch_millis="$(
      minimum_epoch_millis \
        "$runtime_epoch_millis" \
        "$committed_observed_epoch_millis" \
        "$command_observed_epoch_millis"
    )"
    epoch_millis_is_fresh \
      "$observed_epoch_millis" \
      "$validation_epoch_millis" \
      "$maximum_fact_age_millis" || {
      set_observation_failure EVIDENCE_TOO_OLD \
        "Matching partition $partition combined evidence is too old to submit"
      return 2
    }
    observed_at="$(iso_utc_from_epoch_millis "$observed_epoch_millis")" || {
      set_observation_failure INVALID_EVIDENCE \
        "Matching partition $partition observedAt cannot be formatted"
      return 1
    }

    jq -nc \
      --argjson partitionId "$partition" \
      --arg ownerId "$owner_id" \
      --argjson identity "$identity" \
      --argjson committedOffset "$committed_offset" \
      --argjson endOffset "$end_offset" \
      --arg observedAt "$observed_at" \
      '{
        partitionId:$partitionId,
        ownerId:$ownerId,
        state:"READY",
        identity:$identity,
        ownershipPermit:true,
        recoveryComplete:true,
        committedOffset:$committedOffset,
        endOffset:$endOffset,
        observedAt:$observedAt,
        reason:"READY"
      }' >>"$destination" || {
      set_observation_failure INVALID_EVIDENCE \
        "Matching partition $partition status cannot be serialized"
      return 1
    }
  done
}

build_consumer_progress() {
  local rows_key="$1"
  local consumer_state="$2"
  local event_offsets="$3"
  jq -e -n \
    --slurpfile state "$consumer_state" \
    --slurpfile offsets "$event_offsets" \
    --arg rows_key "$rows_key" '
      ($state[0][$rows_key]) as $rows
      | [
          $offsets[0].partitions[] as $end
          | ([ $rows[] | select(.partition_id == $end.partition) ] | first // null) as $row
          | if $row == null then
              if $end.offset == 0 then
                {
                  partitionId:$end.partition,
                  committedOffset:0,
                  endOffset:$end.offset,
                  oldestUnprocessedAge:null
                }
              else
                {
                  partitionId:$end.partition,
                  committedOffset:null,
                  endOffset:$end.offset,
                  oldestUnprocessedAge:null
                }
              end
            else
              {
                partitionId:$end.partition,
                committedOffset:($row.last_processed_offset + 1),
                endOffset:$end.offset,
                oldestUnprocessedAge:null
              }
            end
        ]
      | if length == 15 then .
        else error("critical consumer progress must contain 15 partitions") end
    '
}

consumer_progress_is_caught_up() {
  jq -e '
    length == 15
    and all(.[];
      (.committedOffset | type) == "number"
      and .committedOffset >= 0
      and .committedOffset == .endOffset)
  ' >/dev/null
}

same_json() {
  local left="$1"
  local right="$2"
  jq -e -n --slurpfile left "$left" --slurpfile right "$right" \
    '$left[0] == $right[0]' >/dev/null
}

validate_kafka_position_stability() {
  local attempt_dir="$1"
  same_json \
    "$attempt_dir/matching-commands-before.json" \
    "$attempt_dir/matching-commands-after.json" || {
    set_observation_failure KAFKA_POSITION_CHANGED \
      "matching.commands changed during observation"
    return 2
  }
  same_json \
    "$attempt_dir/matching-events-before.json" \
    "$attempt_dir/matching-events-after.json" || {
    set_observation_failure KAFKA_POSITION_CHANGED \
      "matching.events changed during observation"
    return 2
  }
}

capture_gateway_observation_once() {
  local attempt_dir="$1"
  local destination="$2"
  local expected_active_matching_orders="${3:-0}"
  mkdir -p "$attempt_dir/matching"
  observation_failure_reason=""
  observation_failure_classification=""
  [[ "$expected_active_matching_orders" =~ ^[0-9]+$ ]] || {
    set_observation_failure INVALID_CONFIGURATION \
      "expected active Matching order count is invalid"
    return 1
  }
  date +%s%3N >"$attempt_dir/attempt-started-at"

  local command_after="$attempt_dir/matching-commands-after.json"
  local event_after="$attempt_dir/matching-events-after.json"
  local committed="$attempt_dir/matching-committed-offsets.json"
  local consumer_state="$attempt_dir/consumer-state.json"
  local workloads="$attempt_dir/workloads.json"
  local matching_ndjson="$attempt_dir/matching-partitions.ndjson"

  capture_stable_observation_sources "$attempt_dir" || return 2

  local committed_observed_epoch_millis
  local consumer_observed_epoch_millis
  local workloads_observed_epoch_millis
  local matching_samples_completed_epoch_millis
  local command_observed_epoch_millis
  local event_observed_epoch_millis
  committed_observed_epoch_millis="$(
    read_capture_completion_time "$attempt_dir/matching-committed-observed-at"
  )" || {
    set_observation_failure INVALID_EVIDENCE \
      "Matching committed observation time is unavailable"
    return 1
  }
  consumer_observed_epoch_millis="$(
    read_capture_completion_time "$attempt_dir/consumer-observed-at"
  )" || {
    set_observation_failure INVALID_EVIDENCE \
      "critical-consumer observation time is unavailable"
    return 1
  }
  workloads_observed_epoch_millis="$(
    read_capture_completion_time "$attempt_dir/workloads-observed-at"
  )" || {
    set_observation_failure INVALID_EVIDENCE \
      "workload observation time is unavailable"
    return 1
  }
  matching_samples_completed_epoch_millis="$(
    read_capture_completion_time "$attempt_dir/matching-samples-completed-at"
  )" || {
    set_observation_failure INVALID_EVIDENCE \
      "Matching sample completion time is unavailable"
    return 1
  }
  command_observed_epoch_millis="$(
    read_capture_completion_time "$attempt_dir/matching-commands-observed-at"
  )" || {
    set_observation_failure INVALID_EVIDENCE \
      "matching.commands observation time is unavailable"
    return 1
  }
  event_observed_epoch_millis="$(
    read_capture_completion_time "$attempt_dir/matching-events-observed-at"
  )" || {
    set_observation_failure INVALID_EVIDENCE \
      "matching.events observation time is unavailable"
    return 1
  }

  local validation_epoch_millis
  validation_epoch_millis="$(date +%s%3N)"
  printf '%s\n' "$validation_epoch_millis" >"$attempt_dir/validation-started-at"

  validate_kafka_position_stability "$attempt_dir" || return 2
  required_workloads_are_ready "$workloads" || {
    set_observation_failure WORKLOAD_NOT_READY \
      "one or more required workloads are not fully Ready"
    return 2
  }

  jq -e --argjson expectedActiveMatchingOrders "$expected_active_matching_orders" '
    .persistenceQuarantines == 0
    and .accountQuarantines == 0
    and .quickfixQuarantines == 0
    and .persistenceQuarantineHistory == 0
    and .accountQuarantineHistory == 0
    and .quickfixQuarantineHistory == 0
    and .quickfixPendingIntents == 0
    and .activeMatchingOrders == $expectedActiveMatchingOrders
  ' "$consumer_state" >/dev/null || {
    set_observation_failure INVALID_BASELINE \
      "baseline contains quarantine history, pending FIX delivery, or active orders"
    return 1
  }

  local matching_status=0
  build_matching_partition_statuses \
    "$attempt_dir/matching" \
    "$command_after" \
    "$committed" \
    "$committed_observed_epoch_millis" \
    "$command_observed_epoch_millis" \
    "$matching_samples_completed_epoch_millis" \
    "$validation_epoch_millis" \
    "$matching_ndjson" || matching_status="$?"
  (( matching_status == 0 )) || return "$matching_status"

  local persistence_progress account_progress quickfix_progress
  persistence_progress="$(
    build_consumer_progress persistenceProgress "$consumer_state" "$event_after"
  )" || {
    set_observation_failure INVALID_EVIDENCE \
      "Persistence progress evidence is invalid"
    return 1
  }
  account_progress="$(
    build_consumer_progress accountProgress "$consumer_state" "$event_after"
  )" || {
    set_observation_failure INVALID_EVIDENCE \
      "Account progress evidence is invalid"
    return 1
  }
  quickfix_progress="$(
    build_consumer_progress quickfixProgress "$consumer_state" "$event_after"
  )" || {
    set_observation_failure INVALID_EVIDENCE \
      "QuickFIX progress evidence is invalid"
    return 1
  }

  consumer_progress_is_caught_up <<<"$persistence_progress" || {
    set_observation_failure CONSUMER_PROGRESS_INCOMPLETE \
      "Persistence has not caught up with matching.events"
    return 2
  }
  consumer_progress_is_caught_up <<<"$account_progress" || {
    set_observation_failure CONSUMER_PROGRESS_INCOMPLETE \
      "Account has not caught up with matching.events"
    return 2
  }
  consumer_progress_is_caught_up <<<"$quickfix_progress" || {
    set_observation_failure CONSUMER_PROGRESS_INCOMPLETE \
      "QuickFIX has not caught up with matching.events"
    return 2
  }

  local matching_partitions identity matching_fleet_observed_at
  matching_partitions="$(jq -e -s 'sort_by(.partitionId)' "$matching_ndjson")" || {
    set_observation_failure INVALID_EVIDENCE \
      "Matching fleet status cannot be assembled"
    return 1
  }
  [[ "$(jq 'length' <<<"$matching_partitions")" == 15 ]] || {
    set_observation_failure INVALID_EVIDENCE \
      "Matching fleet status does not contain 15 partitions"
    return 1
  }
  matching_fleet_observed_at="$(
    jq -er 'map(.observedAt) | min | select(type == "string")' <<<"$matching_partitions"
  )" || {
    set_observation_failure INVALID_EVIDENCE \
      "Matching fleet observedAt cannot be derived"
    return 1
  }
  identity="$(identity_json)" || {
    set_observation_failure INVALID_EVIDENCE \
      "system identity cannot be assembled"
    return 1
  }

  local risk_observed_epoch_millis consumer_status_observed_epoch_millis
  local kafka_observed_epoch_millis maximum_fact_age_millis
  risk_observed_epoch_millis="$workloads_observed_epoch_millis"
  consumer_status_observed_epoch_millis="$(
    minimum_epoch_millis \
      "$consumer_observed_epoch_millis" \
      "$workloads_observed_epoch_millis" \
      "$event_observed_epoch_millis"
  )"
  kafka_observed_epoch_millis="$(
    minimum_epoch_millis \
      "$consumer_observed_epoch_millis" \
      "$workloads_observed_epoch_millis" \
      "$command_observed_epoch_millis" \
      "$event_observed_epoch_millis"
  )"
  (( matching_runtime_default_max_age_millis > observation_submission_margin_millis )) || {
    set_observation_failure INVALID_CONFIGURATION \
      "observation submission margin exceeds Gateway freshness threshold"
    return 1
  }
  maximum_fact_age_millis="$((matching_runtime_default_max_age_millis - observation_submission_margin_millis))"

  epoch_millis_is_fresh \
    "$risk_observed_epoch_millis" \
    "$validation_epoch_millis" \
    "$maximum_fact_age_millis" || {
    set_observation_failure EVIDENCE_TOO_OLD \
      "Risk workload observation is too old to submit"
    return 2
  }
  epoch_millis_is_fresh \
    "$consumer_status_observed_epoch_millis" \
    "$validation_epoch_millis" \
    "$maximum_fact_age_millis" || {
    set_observation_failure EVIDENCE_TOO_OLD \
      "critical-consumer observation is too old to submit"
    return 2
  }
  epoch_millis_is_fresh \
    "$kafka_observed_epoch_millis" \
    "$validation_epoch_millis" \
    "$maximum_fact_age_millis" || {
    set_observation_failure EVIDENCE_TOO_OLD \
      "Kafka observation is too old to submit"
    return 2
  }

  local risk_observed_at consumer_observed_at kafka_observed_at
  risk_observed_at="$(iso_utc_from_epoch_millis "$risk_observed_epoch_millis")"
  consumer_observed_at="$(iso_utc_from_epoch_millis "$consumer_status_observed_epoch_millis")"
  kafka_observed_at="$(iso_utc_from_epoch_millis "$kafka_observed_epoch_millis")"

  jq -n \
    --argjson identity "$identity" \
    --argjson matchingPartitions "$matching_partitions" \
    --argjson persistenceProgress "$persistence_progress" \
    --argjson accountProgress "$account_progress" \
    --argjson quickfixProgress "$quickfix_progress" \
    --arg riskObservedAt "$risk_observed_at" \
    --arg matchingFleetObservedAt "$matching_fleet_observed_at" \
    --arg consumerObservedAt "$consumer_observed_at" \
    --arg kafkaObservedAt "$kafka_observed_at" \
    '{
      riskStatus:{
        state:"READY",
        identity:$identity,
        observedAt:$riskObservedAt,
        reason:"deployment Ready at workload observation"
      },
      matchingFleet:{
        partitions:$matchingPartitions,
        observedAt:$matchingFleetObservedAt
      },
      criticalConsumers:[
        {
          component:"PERSISTENCE",
          state:"READY",
          identity:$identity,
          partitionProgress:$persistenceProgress,
          observedAt:$consumerObservedAt,
          reason:"workload Ready and durable progress caught up"
        },
        {
          component:"ACCOUNT",
          state:"READY",
          identity:$identity,
          partitionProgress:$accountProgress,
          observedAt:$consumerObservedAt,
          reason:"workload Ready and durable progress caught up"
        },
        {
          component:"QUICKFIX",
          state:"READY",
          identity:$identity,
          partitionProgress:$quickfixProgress,
          observedAt:$consumerObservedAt,
          reason:"workload Ready and durable progress caught up"
        }
      ],
      kafkaStatus:{
        state:"READY",
        identity:$identity,
        commandPartitionCount:15,
        eventPartitionCount:15,
        sameEventIdDifferentPayload:false,
        observedAt:$kafkaObservedAt,
        reason:"Kafka Ready; positions stable; critical consumers caught up without quarantine history"
      }
    }' >"$destination" || {
    set_observation_failure INVALID_EVIDENCE \
      "Gateway observation cannot be serialized"
    return 1
  }

  date +%s%3N >"$attempt_dir/validation-completed-at"
}

gateway_response_is_retryable_stale() {
  local response="$1"
  jq -e '
    .openEligible == false
    and (.reasons | type == "array" and length > 0)
    and all(.reasons[]; endswith("_STATUS_STALE"))
  ' "$response" >/dev/null
}

capture_gateway_observation() {
  local label="$1"
  local destination="$2"
  local expected_active_matching_orders="${3:-0}"
  local attempt
  local status
  local collection_expiration_streak=0

  for attempt in $(seq 1 "$observation_max_attempts"); do
    local attempt_dir="$evidence_dir/baseline/observation-${label}-attempt-$attempt"
    local classification
    observation_failure_reason=""
    observation_failure_classification=""
    status=0
    capture_gateway_observation_once \
      "$attempt_dir" "$destination" "$expected_active_matching_orders" || status="$?"
    date +%s%3N >"$attempt_dir/attempt-completed-at"
    write_observation_timing "$attempt_dir"

    classification="$observation_failure_classification"
    if [[ -z "$classification" ]]; then
      case "$status" in
        0) classification=ACCEPTED ;;
        1) classification=INVALID_EVIDENCE ;;
        2) classification=RETRYABLE_OBSERVATION ;;
        *) classification=COLLECTION_FAILED ;;
      esac
    fi

    jq -n \
      --argjson attempt "$attempt" \
      --argjson exitStatus "$status" \
      --arg classification "$classification" \
      --arg reason "${observation_failure_reason:-accepted}" \
      '{
        attempt:$attempt,
        exitStatus:$exitStatus,
        retryable:($exitStatus == 2),
        classification:$classification,
        reason:$reason
      }' >"$attempt_dir/result.json"

    case "$status" in
      0)
        return 0
        ;;
      1)
        printf 'system observation rejected: %s\n' "$observation_failure_reason" >&2
        return 1
        ;;
      2)
        if [[ "$classification" == EVIDENCE_EXPIRED_DURING_COLLECTION ]]; then
          collection_expiration_streak="$((collection_expiration_streak + 1))"
          if (( collection_expiration_streak >= 2 )); then
            printf '%s\n' \
              'system observation repeatedly expired fresh evidence during collection' >&2
            return 2
          fi
        else
          collection_expiration_streak=0
        fi
        sleep 0.2
        ;;
      *)
        printf 'system observation failed with status %s: %s\n' \
          "$status" "$observation_failure_reason" >&2
        return "$status"
        ;;
    esac
  done

  printf 'system observation did not stabilize after %s attempts: %s\n' \
    "$observation_max_attempts" \
    "${observation_failure_reason:-unknown transient condition}" >&2
  return 2
}

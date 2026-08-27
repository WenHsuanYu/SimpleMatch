#!/usr/bin/env bash

# Close-specific evidence predicates and bounded observations used by the
# terminal Gateway close certification runner.

close_barriers_advanced_exactly_once() {
  local before="$1"
  local after="$2"
  jq -e -n \
    --slurpfile before "$before" \
    --slurpfile after "$after" '
      ($before[0].partitions) as $beforeRows
      | ($after[0].partitions) as $afterRows
      | ($beforeRows | length) == 15
      and ($afterRows | length) == 15
      and ([$beforeRows[].partition] | sort) == [range(0; 15)]
      and ([$afterRows[].partition] | sort) == [range(0; 15)]
      and all(
        [range(0; 15) as $partition
          | {
              before:($beforeRows[]
                | select(.partition == $partition)
                | .offset),
              after:($afterRows[]
                | select(.partition == $partition)
                | .offset)
            }
        ][];
        .after == (.before + 1)
      )
    ' >/dev/null
}

matching_committed_covers_commands() {
  local commands="$1"
  local committed="$2"
  jq -e -n \
    --slurpfile commands "$commands" \
    --slurpfile committed "$committed" '
      ($commands[0].partitions) as $endRows
      | ($committed[0].partitions) as $committedRows
      | ($endRows | length) == 15
      and ($committedRows | length) == 15
      and ([$endRows[].partition] | sort) == [range(0; 15)]
      and ([$committedRows[].partition] | sort) == [range(0; 15)]
      and all(
        [range(0; 15) as $partition
          | {
              end:($endRows[]
                | select(.partition == $partition)
                | .offset),
              committed:($committedRows[]
                | select(.partition == $partition)
                | .committedOffset)
            }
        ][];
        .committed >= .end
      )
    ' >/dev/null
}

critical_consumers_cover_events() {
  local events="$1"
  local consumers="$2"
  jq -e -n \
    --slurpfile events "$events" \
    --slurpfile consumers "$consumers" '
      def covers($rows; $partition; $endOffset):
        if $endOffset == 0 then true
        else
          any(
            $rows[]?;
            .partition_id == $partition
            and .last_processed_offset >= ($endOffset - 1)
          )
        end;
      ($events[0].partitions) as $endRows
      | ($consumers[0]) as $state
      | ($endRows | length) == 15
      and ([$endRows[].partition] | sort) == [range(0; 15)]
      and all(
        $endRows[];
        . as $end
        | covers($state.persistenceProgress; $end.partition; $end.offset)
        and covers($state.accountProgress; $end.partition; $end.offset)
        and covers($state.quickfixProgress; $end.partition; $end.offset)
      )
      and $state.persistenceQuarantines == 0
      and $state.accountQuarantines == 0
      and $state.quickfixQuarantines == 0
      and $state.persistenceQuarantineHistory == 0
      and $state.accountQuarantineHistory == 0
      and $state.quickfixQuarantineHistory == 0
    ' >/dev/null
}

capture_order_projection() {
  local selected_order_id="$1"
  local destination="$2"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1

  local projection
  projection="$(
    kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
      -v ON_ERROR_STOP=1 -c "
        SELECT json_build_object(
          'orderId', order_id::text,
          'status', status,
          'cumulativeQuantityShares', cumulative_quantity_shares,
          'leavesQuantityShares', leaves_quantity_shares,
          'lastEventId', encode(last_event_id, 'hex')
        )::text
        FROM persistence.matching_order_projections
        WHERE order_id = '$selected_order_id';
      " 2>/dev/null
  )" || return 1
  [[ -n "$projection" ]] || return 1
  printf '%s\n' "$projection" | jq . >"$destination"
}

wait_order_projection_status() {
  local selected_order_id="$1"
  local expected="$2"
  local destination="$3"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    if capture_order_projection "$selected_order_id" "$destination" \
        && jq -e --arg expected "$expected" '.status == $expected' \
          "$destination" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_close_barriers_published() {
  local before="$1"
  local after="$2"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    if capture_kafka_matching_commands_end_positions "$after" \
        && close_barriers_advanced_exactly_once "$before" "$after"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

verify_close_barrier_payloads() {
  local before="$1"
  local after="$2"
  local destination="$3"
  capture_kafka_close_barriers "$before" "$after" "$destination"
}

wait_matching_committed_to_close() {
  local commands="$1"
  local committed="$2"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    if capture_kafka_matching_committed_positions "$committed" \
        && matching_committed_covers_commands "$commands" "$committed"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

capture_matching_closed_partition() {
  local partition="$1"
  local samples_dir="$2"
  local destination="$3"
  local metrics="$samples_dir/partition-$partition-runtime.json"
  local pod="$samples_dir/partition-$partition-pod-after.json"
  local now_epoch_millis

  capture_matching_partition_sample "$partition" "$samples_dir" false || return 1
  now_epoch_millis="$(date +%s%3N)"
  matching_runtime_is_closed \
    "$now_epoch_millis" "$matching_runtime_default_max_age_millis" <"$metrics" || return 1

  jq -e -n \
    --argjson partition "$partition" \
    --slurpfile runtime "$metrics" \
    --slurpfile pod "$pod" '
      ($pod[0].metadata.uid // "") as $podUid
      | select($podUid | length > 0)
      | {partitionId:$partition, podUid:$podUid, runtime:$runtime[0]}
    ' >"$destination"
}

capture_matching_closed_state() {
  local destination="$1"
  local partitions_dir="${destination%.json}-partitions"
  local samples_dir="$partitions_dir/samples"
  mkdir -p "$partitions_dir"
  mkdir -p "$samples_dir"

  local partition
  local -a pids=()
  local failed=false
  for partition in $(seq 0 14); do
    capture_matching_closed_partition \
      "$partition" "$samples_dir" "$partitions_dir/partition-$partition.json" &
    pids+=("$!")
  done

  local pid
  for pid in "${pids[@]}"; do
    wait "$pid" || failed=true
  done
  [[ "$failed" == false ]] || return 1

  local -a partition_files=()
  for partition in $(seq 0 14); do
    partition_files+=("$partitions_dir/partition-$partition.json")
  done
  jq -s 'sort_by(.partitionId)' "${partition_files[@]}" >"$destination" || return 1
  jq -e '
    length == 15
    and ([.[].partitionId] == [range(0; 15)])
    and all(.[];
      .podUid != ""
      and .runtime.runtime_state == "RUNNING"
      and .runtime.partition_state == "CLOSED"
      and .runtime.pending_inputs == 0
      and .runtime.pending_publications == 0)
  ' "$destination" >/dev/null
}

wait_matching_closed() {
  local destination="$1"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    if capture_matching_closed_state "$destination"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_critical_consumers_to_close() {
  local events="$1"
  local consumers="$2"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    if capture_consumer_state "$consumers" \
        && critical_consumers_cover_events "$events" "$consumers"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# shellcheck shell=bash

matching_e2e_runtime_caught_up() {
  local metrics_json="$1"
  local expected_end_offset="$2"

  jq -e --argjson end_offset "$expected_end_offset" '
    .runtime_state == "READY"
    and .pending_inputs == 0
    and .pending_publications == 0
    and (
      ((.next_commit_offset | type) == "number"
        and .next_commit_offset >= $end_offset)
      or
      (.next_commit_offset == null
        and (.highest_contiguous_completed_offset | type) == "number"
        and .highest_contiguous_completed_offset >= ($end_offset - 1))
    )
  ' <<<"$metrics_json" >/dev/null
}

matching_e2e_report_is_valid() {
  local metrics_file="$1"
  local runtime_evidence="$2"

  jq -e --argjson runtime_evidence "$runtime_evidence" '
    .status == "PASSED" and
    (.kafka_e2e_latency_ns | type == "object") and
    ([.kafka_e2e_latency_ns.p50, .kafka_e2e_latency_ns.p99,
      .kafka_e2e_latency_ns.p99_9, .kafka_e2e_latency_ns.max]
      | all(. != null and (type == "number") and . >= 0)) and
    (.kafka_e2e_latency_definition | type == "string" and length > 0) and
    (.ring_occupancy | type == "object") and
    (.ring_occupancy.before | type == "array" and length == 15 and
      all(.runtime_state == "READY" and (.input_ring | type == "object") and
          (.output_ring | type == "object"))) and
    (.ring_occupancy.after | type == "array" and length == 15 and
      all(.runtime_state == "READY" and (.input_ring | type == "object") and
          (.output_ring | type == "object"))) and
    (.loss | type == "number" and . == 0) and
    (.duplicates | type == "number" and . == 0) and
    (.replay_lag_seconds | type == "number" and . >= 0 and . <= 60) and
    (.replacement_seconds | type == "number" and . >= 0 and . <= 120) and
    (.command_end_offset | type == "number") and
    (.fault_mode == "pod-delete" or .fault_mode == "process-crash") and
    (.target.old_uid | type == "string" and length > 0) and
    (.target.new_uid | type == "string" and length > 0) and
    (.target.old_node | type == "string" and length > 0) and
    (.target.new_node == .target.old_node) and
    (.target.pvc | type == "string" and length > 0) and
    (.target.pv | type == "string" and length > 0) and
    (.target.old_restart_count | type == "number" and . >= 0) and
    (.target.new_restart_count | type == "number" and . >= 0) and
    (if .fault_mode == "process-crash"
     then .target.new_uid == .target.old_uid and .target.new_restart_count > .target.old_restart_count
     else .target.new_uid != .target.old_uid
     end) and
    (.evidence.e2e_before | type == "string" and length > 0) and
    (.evidence.e2e_after | type == "string" and length > 0) and
    ($runtime_evidence | type == "array" and length == 15 and
      ([.[] | .pod] | unique | length == 15) and
      ([.[] | select(
        (.pod | type == "string" and length > 0) and
        (.uid | type == "string" and length > 0) and
        (.node | type == "string" and length > 0))] | length == 15))
  ' "$metrics_file" >/dev/null
}

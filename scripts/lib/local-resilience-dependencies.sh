#!/usr/bin/env bash

# Report validation for the focused PostgreSQL, Redis, and Kafka recovery seam.
# The runtime adapter may collect Kubernetes/Docker facts in any order; callers
# only need to present one immutable JSON report to this module. Keeping the
# assertions here makes the evidence contract small, deterministic, and easy to
# exercise without a cluster.

set -euo pipefail

readonly RESILIENCE_DEPENDENCY_REPORT_SCHEMA_VERSION=1
readonly RESILIENCE_DEPENDENCY_KAFKA_CLUSTER_ID='5L6g3nShT-eMCtK--X86sw'

resilience_dependency_valid_component() {
  case "$1" in
    postgresql|redis|kafka) return 0 ;;
    *) return 1 ;;
  esac
}

resilience_dependency_report_is_valid() {
  local component="$1"
  local report_path="$2"

  resilience_dependency_valid_component "$component" || return 1
  [[ -f "$report_path" ]] || return 1

  case "$component" in
    postgresql)
      jq -e --arg component "$component" --argjson schema_version "$RESILIENCE_DEPENDENCY_REPORT_SCHEMA_VERSION" '
        def text: type == "string" and length > 0;
        def common:
          .schema_version == $schema_version and
          .profile == "dependency-recovery" and
          .component == $component and
          (.status as $status | (["PASSED", "FAILED", "UNSUPPORTED", "BLOCKED"] | index($status) != null)) and
          (.cluster | text) and (.context | text) and (.namespace | text) and (.run_id | text) and
          (.fault_mode == "worker-stop" or .fault_mode == "pod-restart") and
          (.deadline_seconds | type == "number" and floor == . and . > 0 and . <= 300) and
          (.target | type == "object") and
          (.claim_boundary | type == "array") and
          (.failure_reason == null or (.failure_reason | text));
        def docker_id: type == "string" and test("^[0-9a-f]{64}$");
        def worker_stop:
          (.worker_stop | type == "object") and
          (.worker_stop.node | text) and
          (.worker_stop.container_id | docker_id) and
          (.worker_stop.container_id_after | docker_id) and
          (.worker_stop.container_id_after == .worker_stop.container_id) and
          (.worker_stop.node_not_ready_observed == true) and
          (.worker_stop.same_container_restarted == true);
        common and
        (if .status == "PASSED" then
          (.failure_reason == null) and
          (.target.before | type == "object") and (.target.after | type == "object") and
          (.target.before.pod == "postgres-0") and (.target.after.pod == "postgres-0") and
          (.target.before.pod_uid | text) and (.target.after.pod_uid | text) and
          (.target.before.node | text) and (.target.after.node | text) and
          (.target.before.node == .target.after.node) and
          (.target.before.worker_slot == "0") and (.target.after.worker_slot == "0") and
          (.target.before.pvc | text) and (.target.after.pvc | text) and
          (.target.before.pvc == .target.after.pvc) and
          (.target.before.pv | text) and (.target.after.pv | text) and
          (.target.before.pv == .target.after.pv) and
          (if .fault_mode == "worker-stop" then
            (worker_stop and .worker_stop.node == .target.before.node)
          else (.target.before.pod_uid != .target.after.pod_uid) end) and
          (.recovery | type == "object") and
          (.recovery.ready == true) and (.recovery.durable_marker | text) and
          (.recovery.durable_before == true) and (.recovery.durable_after == true) and
          (.recovery.data_preserved == true)
        else (.failure_reason | text) end)
      ' "$report_path" >/dev/null
      ;;
    redis)
      jq -e --arg component "$component" --argjson schema_version "$RESILIENCE_DEPENDENCY_REPORT_SCHEMA_VERSION" '
        def text: type == "string" and length > 0;
        def common:
          .schema_version == $schema_version and
          .profile == "dependency-recovery" and
          .component == $component and
          (.status as $status | (["PASSED", "FAILED", "UNSUPPORTED", "BLOCKED"] | index($status) != null)) and
          (.cluster | text) and (.context | text) and (.namespace | text) and (.run_id | text) and
          (.fault_mode == "worker-stop" or .fault_mode == "pod-restart") and
          (.deadline_seconds | type == "number" and floor == . and . > 0 and . <= 300) and
          (.target | type == "object") and
          (.claim_boundary | type == "array") and
          (.failure_reason == null or (.failure_reason | text));
        def docker_id: type == "string" and test("^[0-9a-f]{64}$");
        def worker_stop:
          (.worker_stop | type == "object") and
          (.worker_stop.node | text) and
          (.worker_stop.container_id | docker_id) and
          (.worker_stop.container_id_after | docker_id) and
          (.worker_stop.container_id_after == .worker_stop.container_id) and
          (.worker_stop.node_not_ready_observed == true) and
          (.worker_stop.same_container_restarted == true);
        common and
        (if .status == "PASSED" then
          (.failure_reason == null) and
          (.target.before | type == "object") and (.target.after | type == "object") and
          (.target.before.pod | text) and (.target.after.pod | text) and
          (.target.before.pod_uid | text) and (.target.after.pod_uid | text) and
          (.target.before.node | text) and (.target.after.node | text) and
          (.target.before.worker_slot | text) and (.target.after.worker_slot | text) and
          (.target.before.pvc == null) and (.target.after.pvc == null) and
          (if .fault_mode == "worker-stop" then
            (worker_stop and .worker_stop.node == .target.before.node)
          else (.target.before.pod_uid != .target.after.pod_uid) end) and
          (.recovery | type == "object") and
          (.recovery.ready == true) and (.recovery.portable == true) and
          (.recovery.rescheduled_after_worker_loss ==
            (if .fault_mode == "worker-stop" then true else false end)) and
          (.recovery.disposable_state == true) and
          (.recovery.marker_before == true) and (.recovery.marker_after | type == "boolean") and
          (.recovery.marker_required_after == false)
        else (.failure_reason | text) end)
      ' "$report_path" >/dev/null
      ;;
    kafka)
      jq -e --arg component "$component" --arg cluster_id "$RESILIENCE_DEPENDENCY_KAFKA_CLUSTER_ID" --argjson schema_version "$RESILIENCE_DEPENDENCY_REPORT_SCHEMA_VERSION" '
        def text: type == "string" and length > 0;
        def common:
          .schema_version == $schema_version and
          .profile == "dependency-recovery" and
          .component == $component and
          (.status as $status | (["PASSED", "FAILED", "UNSUPPORTED", "BLOCKED"] | index($status) != null)) and
          (.cluster | text) and (.context | text) and (.namespace | text) and (.run_id | text) and
          (.fault_mode == "worker-stop" or .fault_mode == "pod-restart") and
          (.deadline_seconds | type == "number" and floor == . and . > 0 and . <= 300) and
          (.target | type == "object") and
          (.claim_boundary | type == "array") and
          (.failure_reason == null or (.failure_reason | text));
        def docker_id: type == "string" and test("^[0-9a-f]{64}$");
        def worker_stop:
          (.worker_stop | type == "object") and
          (.worker_stop.node | text) and
          (.worker_stop.container_id | docker_id) and
          (.worker_stop.container_id_after | docker_id) and
          (.worker_stop.container_id_after == .worker_stop.container_id) and
          (.worker_stop.node_not_ready_observed == true) and
          (.worker_stop.same_container_restarted == true);
        def broker_set:
          type == "array" and length == 3 and
          ([.[].node_id] | sort) == [0, 1, 2] and
          ([.[].node] | unique | length) == 3 and
          ([.[].worker_slot] | sort) == ["0", "1", "2"] and
          all(.[];
            (.pod == ("kafka-" + (.node_id | tostring))) and
            (.pod_uid | text) and (.node | text) and (.worker_slot | text) and
            (.pvc | text) and (.pv | text) and (.cluster_id == $cluster_id) and
            (.node_id | type == "number" and floor == . and . >= 0 and . <= 2)
          );
        common and
        (if .status == "PASSED" then
          (.failure_reason == null) and
          (.target.ordinal | type == "number" and floor == . and . >= 0 and . <= 2) and
          (.target.before | type == "object") and (.target.after | type == "object") and
          (.target.before.pod == ("kafka-" + (.target.ordinal | tostring))) and
          (.target.after.pod == ("kafka-" + (.target.ordinal | tostring))) and
          (.target.before.pod_uid | text) and (.target.after.pod_uid | text) and
          (.target.before.node | text) and (.target.after.node | text) and
          (.target.before.node == .target.after.node) and
          (.target.before.worker_slot | text) and (.target.after.worker_slot | text) and
          (.target.before.worker_slot == .target.after.worker_slot) and
          (.target.before.pvc | text) and (.target.after.pvc | text) and
          (.target.before.pvc == .target.after.pvc) and
          (.target.before.pv | text) and (.target.after.pv | text) and
          (.target.before.pv == .target.after.pv) and
          (.target.before.cluster_id == $cluster_id) and (.target.after.cluster_id == $cluster_id) and
          (.target.before.node_id == .target.ordinal) and (.target.after.node_id == .target.ordinal) and
          (.brokers_before | broker_set) and (.brokers_after | broker_set) and
          (if .fault_mode == "worker-stop" then
            (worker_stop and .worker_stop.node == .target.before.node)
          else (.target.before.pod_uid != .target.after.pod_uid) end) and
          (.quorum | type == "object") and (.quorum.ready_before == true) and
          (.quorum.available_during | type == "number" and floor == . and . >= 2) and
          (.quorum.isr_before == 3) and (.quorum.isr_after == 3) and (.quorum.restored == true) and
          (.marker | type == "object") and (.marker.topic | text) and (.marker.key | text) and
          (.marker.committed_before == true) and (.marker.preserved_after == true) and
          (.marker.record_count_before | type == "number" and . >= 1) and
          (.marker.record_count_after | type == "number" and . >= 1) and
          (.topic_contract | type == "object") and (.topic_contract.verified == true) and
          (.topic_contract.topics == ["matching.commands", "matching.events", "account.lifecycle", "marketdata.events", "simplematch-connect-configs", "simplematch-connect-offsets", "simplematch-connect-status"]) and
          (.topic_contract.producer_acks == "all") and
          (.topic_contract.producer_idempotence == true) and
          (.recovery | type == "object") and (.recovery.ready == true) and
          (.recovery.rejoined == true) and (.recovery.formatted_again == false) and
          (.recovery.catch_up_complete == true) and
          (.recovery.catch_up_probe == "log-dirs-offset-lag-zero")
        else (.failure_reason | text) end)
      ' "$report_path" >/dev/null
      ;;
  esac
}

resilience_dependency_report_is_passed() {
  local component="$1"
  local report_path="$2"

  resilience_dependency_report_is_valid "$component" "$report_path" || return 1
  jq -e '.status == "PASSED"' "$report_path" >/dev/null
}

resilience_dependency_report_status() {
  local report_path="$1"
  jq -er '.status | select(type == "string" and length > 0)' "$report_path"
}

resilience_dependency_report_failure_reason() {
  local report_path="$1"
  jq -r '.failure_reason // empty' "$report_path"
}

#!/usr/bin/env bash

# Kafka Connect worker-loss evidence Module.
#
# Interface:
#   connect_worker_loss_status_is_valid <status-file>
#   connect_worker_loss_pods_are_valid <pods-file>
#   connect_worker_loss_target_identity <status-file> <pods-file> <output-file>
#   connect_worker_loss_assert_reassignment <before-status> <after-status> \
#     <before-target> <after-target>
#   connect_worker_loss_report_is_valid <report-file>
#   connect_worker_loss_report_is_passed <report-file>
#
# A passed report must link every claim to immutable files in the report
# directory: status/Pod snapshots, the UID-guarded delete evidence, scoped
# provenance, and the shared CDC baseline/probe snapshots.
#
# The Module owns the interpretation of Connect task status and the mapping from a
# Connect worker id to a Ready Pod. The runner only supplies Kubernetes and REST
# Adapters, injects the Pod loss, and delegates CDC observation to cdc-verifier.sh.

# Version 2 adds scoped provenance fields and the executable verifier-contract
# evidence link. A report from version 1 is intentionally not upgraded.
CONNECT_WORKER_LOSS_EVIDENCE_VERIFIER="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/connect-worker-loss-evidence.rb"

connect_worker_loss_report_schema_version() {
  printf '%s\n' 2
}

connect_worker_loss_max_deadline_seconds() {
  printf '%s\n' 900
}

connect_worker_loss_default_deadline_seconds() {
  printf '%s\n' 600
}

connect_worker_loss_setup_deadline_seconds() {
  printf '%s\n' 300
}

_connect_worker_loss_fail() {
  printf 'Connect worker-loss verifier: %s\n' "$*" >&2
  return 1
}

connect_worker_loss_status_is_valid() {
  local status_file="$1"

  [[ -s "$status_file" ]] ||
    _connect_worker_loss_fail "Connect status is missing or empty: $status_file" || return 1
  jq -e '
    (.name == "account-service-outbox") and
    (.connector.state == "RUNNING") and
    (.tasks | type == "array" and length == 1) and
    (.tasks[0].id | type == "number" and floor == . and . >= 0) and
    (.tasks[0].state == "RUNNING") and
    (.tasks[0].worker_id | type == "string" and length > 0)
  ' "$status_file" >/dev/null ||
    _connect_worker_loss_fail \
      "Connect status must contain one RUNNING task owned by a worker: $status_file" || return 1
}

connect_worker_loss_pods_are_valid() {
  local pods_file="$1"

  [[ -s "$pods_file" ]] ||
    _connect_worker_loss_fail "Connect Pod snapshot is missing or empty: $pods_file" || return 1
  jq -e '
    def ready:
      any(.status.conditions[]?; .type == "Ready" and .status == "True");
    [.items[]
      | select((.metadata.deletionTimestamp // null) == null)
      | select(ready)] as $ready |
    ($ready | length == 2) and
    ($ready | all(
      (.metadata.name | type == "string" and length > 0) and
      (.metadata.labels["app.kubernetes.io/name"] == "kafka-connect") and
      (.metadata.labels["app.kubernetes.io/component"] == "connector") and
      (.metadata.uid | type == "string" and length > 0) and
      (.spec.nodeName | type == "string" and length > 0) and
      (.status.podIP | type == "string" and length > 0) and
      ((.spec.volumes // []) | all(.persistentVolumeClaim == null))
    )) and
    (($ready | map(.spec.nodeName) | unique | length) == 2) and
    (($ready | map(.status.podIP) | unique | length) == 2)
  ' "$pods_file" >/dev/null ||
    _connect_worker_loss_fail \
      "Connect must have exactly two Ready, non-terminating Pods on distinct nodes without PVCs: $pods_file" || return 1
}

_connect_worker_loss_worker_host() {
  local worker_id="$1"

  if [[ "$worker_id" =~ ^([^:]+):[0-9]+$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  else
    printf '%s\n' "$worker_id"
  fi
}

_connect_worker_loss_owner_identity_is_valid() {
  local identity_file="$1" label="$2"

  [[ -s "$identity_file" ]] ||
    _connect_worker_loss_fail "$label task owner identity is missing: $identity_file" ||
    return 1
  jq -e '
    type == "object" and
    (.task_id | type == "number" and floor == . and . >= 0) and
    (.worker_id | type == "string" and length > 0) and
    (.worker_host | type == "string" and length > 0) and
    (.pod | type == "string" and length > 0) and
    (.pod_uid | type == "string" and length > 0) and
    (.node | type == "string" and length > 0) and
    (.pod_ip | type == "string" and length > 0) and
    (.ready == true)
  ' "$identity_file" >/dev/null ||
    _connect_worker_loss_fail "$label task owner identity is invalid: $identity_file" ||
    return 1
}

connect_worker_loss_target_identity() {
  local status_file="$1" pods_file="$2" output_file="$3"
  local worker_id worker_host task_id target_json

  [[ -n "$output_file" ]] ||
    _connect_worker_loss_fail 'target identity output path is required' || return 1
  connect_worker_loss_status_is_valid "$status_file" || return 1
  connect_worker_loss_pods_are_valid "$pods_file" || return 1

  worker_id="$(jq -er '.tasks[0].worker_id' "$status_file")" || return 1
  worker_host="$(_connect_worker_loss_worker_host "$worker_id")" || return 1
  task_id="$(jq -er '.tasks[0].id | tostring' "$status_file")" || return 1
  target_json="$(jq -c --arg worker_host "$worker_host" '
    def ready:
      any(.status.conditions[]?; .type == "Ready" and .status == "True");
    [.items[]
      | select((.metadata.deletionTimestamp // null) == null)
      | select(ready and .status.podIP == $worker_host)]
    | if length == 1 then .[0] else empty end
  ' "$pods_file")"
  [[ -n "$target_json" ]] ||
    _connect_worker_loss_fail \
      "Connect task worker $worker_id does not map to exactly one Ready Pod" || return 1

  jq -n \
    --arg worker_id "$worker_id" \
    --arg worker_host "$worker_host" \
    --argjson task_id "$task_id" \
    --argjson pod "$target_json" \
    '{task_id:$task_id,worker_id:$worker_id,worker_host:$worker_host,
      pod:$pod.metadata.name,pod_uid:$pod.metadata.uid,node:$pod.spec.nodeName,
      pod_ip:$pod.status.podIP,ready:true}' >"$output_file" || return 1
  _connect_worker_loss_owner_identity_is_valid "$output_file" target
}

connect_worker_loss_assert_reassignment() {
  local before_status="$1" after_status="$2" before_target="$3" after_target="$4"
  local before_worker after_worker before_task after_task before_uid after_uid

  connect_worker_loss_status_is_valid "$before_status" || return 1
  connect_worker_loss_status_is_valid "$after_status" || return 1
  _connect_worker_loss_owner_identity_is_valid "$before_target" before || return 1
  _connect_worker_loss_owner_identity_is_valid "$after_target" after || return 1

  before_worker="$(jq -er '.tasks[0].worker_id' "$before_status")" || return 1
  after_worker="$(jq -er '.tasks[0].worker_id' "$after_status")" || return 1
  before_task="$(jq -er '.tasks[0].id' "$before_status")" || return 1
  after_task="$(jq -er '.tasks[0].id' "$after_status")" || return 1
  before_uid="$(jq -er '.pod_uid' "$before_target")" || return 1
  after_uid="$(jq -er '.pod_uid' "$after_target")" || return 1

  [[ "$before_task" == "$after_task" ]] ||
    _connect_worker_loss_fail \
      "Connect task id changed across worker loss: before=$before_task after=$after_task" || return 1
  [[ "$before_worker" != "$after_worker" ]] ||
    _connect_worker_loss_fail \
      'Connect REST status still reports the original task worker after Pod loss' || return 1
  [[ "$before_uid" != "$after_uid" ]] ||
    _connect_worker_loss_fail \
      'Connect task owner Pod identity did not change after worker loss' || return 1
  [[ "$(jq -r '.worker_id' "$before_target")" == "$before_worker" &&
    "$(jq -r '.worker_id' "$after_target")" == "$after_worker" ]] ||
    _connect_worker_loss_fail \
      'task owner identity snapshots disagree with Connect REST status' || return 1
}

connect_worker_loss_report_is_valid() {
  local report_file="$1"

  ruby "$CONNECT_WORKER_LOSS_EVIDENCE_VERIFIER" envelope "$report_file"
}

connect_worker_loss_report_is_passed() {
  local report_file="$1" probe publication_evidence

  ruby "$CONNECT_WORKER_LOSS_EVIDENCE_VERIFIER" passed "$report_file" || return 1
  probe="$(dirname -- "$report_file")/$(jq -er \
    '.evidence.probe_file | select(type == "string" and length > 0)' \
    "$report_file")" || return 1
  publication_evidence="$(dirname -- "$report_file")/$(jq -er \
    '.evidence.publication_evidence_file | select(type == "string" and length > 0)' \
    "$report_file")" || return 1
  declare -F cdc_validate_probe >/dev/null ||
    _connect_worker_loss_fail 'shared CDC probe validator is unavailable' || return 1
  declare -F cdc_validate_publication_evidence >/dev/null ||
    _connect_worker_loss_fail 'shared CDC publication evidence validator is unavailable' || return 1
  cdc_validate_probe "$probe" || return 1
  cdc_validate_publication_evidence "$publication_evidence"
}

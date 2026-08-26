#!/usr/bin/env bash

# In-cluster Kafka observation adapter for the failure-certification runner.
# The Java process keeps one Kafka Admin client warm while shell orchestration
# controls when each snapshot is taken.

start_kafka_observation_adapter() {
  kns get pod "$kafka_observer_pod" >/dev/null 2>&1 &&
    die "Kafka observation Pod already exists: $kafka_observer_pod"
  kns create -f "$kafka_observer_manifest" >/dev/null
  kns wait --for=condition=Ready "pod/$kafka_observer_pod" \
    --timeout="${timeout_seconds}s" >/dev/null

  stop_background_process "${kafka_observer_port_forward_pid:-}"
  kafka_observer_port_forward_pid=""
  kafka_observer_port=""
  start_port_forward "pod/$kafka_observer_pod" 8081 \
    "$evidence_dir/baseline/kafka-observer-port-forward.log" \
    kafka_observer_port_forward_pid kafka_observer_port ||
    die 'Kafka observation port-forward did not become ready'
}

stop_kafka_observation_adapter() {
  stop_background_process "${kafka_observer_port_forward_pid:-}"
  kafka_observer_port_forward_pid=""
  kafka_observer_port=""
}

kafka_observation_request() {
  local path="$1"
  local destination="$2"
  local status
  status="$(curl --connect-timeout 2 --max-time 4 -sS -o "$destination" -w '%{http_code}' \
    -X POST "http://127.0.0.1:${kafka_observer_port}${path}")" || return 1
  [[ "$status" == 200 ]]
}

capture_kafka_log_end_positions() {
  local commands_destination="$1"
  local events_destination="$2"
  local response="${commands_destination%.json}-session-response.json"

  kafka_observation_request /log-end-positions "$response" || return 1
  jq -e '.matchingCommands' "$response" >"$commands_destination" || return 1
  jq -e '.matchingEvents' "$response" >"$events_destination" || return 1
}

capture_kafka_matching_committed_positions() {
  local destination="$1"
  kafka_observation_request /matching-committed-positions "$destination" || return 1
  jq -e '
    .topic == "matching.commands"
    and (.partitions | length) == 15
    and ([.partitions[].partition] == [range(0; 15)])
    and all(.partitions[]; .committedOffset >= 0)
  ' "$destination" >/dev/null
}

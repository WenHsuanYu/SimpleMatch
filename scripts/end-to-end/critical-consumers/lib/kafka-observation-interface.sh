#!/usr/bin/env bash

# In-cluster Kafka observation adapter for the failure-certification runner.
# The Java process keeps one Kafka Admin client warm while shell orchestration
# controls when each snapshot is taken.

prepare_verifier_helper_manifests() {
  [[ "${verifier_helper_manifests_prepared:-false}" == true ]] && return 0

  local verifier_image_reference helper_dir
  local kafka_source_manifest="$kafka_observer_manifest"
  local event_source_manifest="$observer_manifest"
  verifier_image_reference="$(
    simplematch_certification_verifier_image "$repo_root" "$namespace"
  )" || return 1

  helper_dir="$evidence_dir/baseline/verifier-helper-manifests"
  mkdir -p "$helper_dir"
  simplematch_render_verifier_helper_manifest \
    "$kafka_source_manifest" "$verifier_image_reference" \
    "$helper_dir/kafka-observer.yaml" || return 1
  simplematch_render_verifier_helper_manifest \
    "$event_source_manifest" "$verifier_image_reference" \
    "$helper_dir/matching-event-observer.yaml" || return 1

  kafka_observer_manifest="$helper_dir/kafka-observer.yaml"
  observer_manifest="$helper_dir/matching-event-observer.yaml"
  jq -n \
    --arg sourceRevision "$(git -C "$repo_root" rev-parse HEAD)" \
    --arg verifierImage "$verifier_image_reference" \
    --arg productionLikeEvidenceDir "$(simplematch_production_like_evidence_dir "$repo_root")" \
    '{
      sourceRevision:$sourceRevision,
      verifierImage:$verifierImage,
      productionLikeEvidenceDir:$productionLikeEvidenceDir
    }' >"$evidence_dir/baseline/verifier-helper-provenance.json"
  verifier_helper_manifests_prepared=true
}

capture_kafka_observer_startup_diagnostics() {
  local diagnostic_dir="$evidence_dir/diagnostics/kafka-observer-startup"
  mkdir -p "$diagnostic_dir"
  kns get "pod/$kafka_observer_pod" -o yaml \
    >"$diagnostic_dir/pod.yaml" 2>"$diagnostic_dir/pod.stderr" || true
  kns describe "pod/$kafka_observer_pod" \
    >"$diagnostic_dir/describe.txt" 2>&1 || true
  kns logs "$kafka_observer_pod" -c observer --tail=300 \
    >"$diagnostic_dir/observer.log" 2>"$diagnostic_dir/observer.stderr" || true
}

start_kafka_observation_adapter() {
  prepare_verifier_helper_manifests ||
    die 'retained production-like source or verifier image provenance is not valid'
  kns get pod "$kafka_observer_pod" >/dev/null 2>&1 &&
    die "Kafka observation Pod already exists: $kafka_observer_pod"
  kns create -f "$kafka_observer_manifest" >/dev/null
  if ! kns wait --for=condition=Ready "pod/$kafka_observer_pod" \
      --timeout="${timeout_seconds}s" >/dev/null; then
    capture_kafka_observer_startup_diagnostics
    die 'Kafka observation Pod did not become Ready; inspect diagnostics/kafka-observer-startup'
  fi

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

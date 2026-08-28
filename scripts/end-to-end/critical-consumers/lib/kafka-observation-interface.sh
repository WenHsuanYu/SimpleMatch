#!/usr/bin/env bash

# In-cluster Kafka observation adapter for certification runners.
# One warm Kafka Admin client serves repeated position snapshots without
# starting a new Kafka CLI/JVM process for each observation.

record_verifier_helper_provenance() {
  local retained_evidence_dir="$1"
  local verifier_image_reference="$2"
  local source_revision verifier_image_identity

  source_revision="$(simplematch_certification_source_revision "$repo_root")" || return 1
  verifier_image_identity="$(
    simplematch_certification_verifier_image_identity "$retained_evidence_dir"
  )" || return 1
  jq -n \
    --arg sourceRevision "$source_revision" \
    --arg verifierImage "$verifier_image_reference" \
    --arg verifierImageIdentity "$verifier_image_identity" \
    --arg productionLikeEvidenceDir "$retained_evidence_dir" \
    '{
      sourceRevision:$sourceRevision,
      verifierImage:$verifierImage,
      verifierImageIdentity:$verifierImageIdentity,
      productionLikeEvidenceDir:$productionLikeEvidenceDir
    }' >"$evidence_dir/baseline/verifier-helper-provenance.json"
}

render_verifier_helper_manifest() {
  local source_manifest="$1"
  local destination="$2"
  local retained_evidence_dir="$3"
  local verifier_image_reference

  verifier_image_reference="$(
    simplematch_certification_verifier_image \
      "$repo_root" "$namespace" "$retained_evidence_dir"
  )" || return 1

  simplematch_render_verifier_helper_manifest \
    "$source_manifest" "$verifier_image_reference" "$destination" || return 1
  record_verifier_helper_provenance \
    "$retained_evidence_dir" "$verifier_image_reference"
}

prepare_kafka_observer_manifest() {
  local retained_evidence_dir="${1:-$(simplematch_production_like_evidence_dir "$repo_root")}"
  [[ "${kafka_observer_manifest_prepared:-false}" == true ]] && return 0

  local helper_dir="$evidence_dir/baseline/verifier-helper-manifests"
  mkdir -p "$helper_dir" || return 1
  render_verifier_helper_manifest \
    "$kafka_observer_manifest" \
    "$helper_dir/kafka-observer.yaml" \
    "$retained_evidence_dir" || return 1

  kafka_observer_manifest="$helper_dir/kafka-observer.yaml"
  kafka_observer_manifest_prepared=true
}

prepare_matching_event_observer_manifest() {
  local retained_evidence_dir="${1:-$(simplematch_production_like_evidence_dir "$repo_root")}"
  [[ "${matching_event_observer_manifest_prepared:-false}" == true ]] && return 0
  [[ -n "${observer_manifest:-}" ]] || return 0

  local helper_dir="$evidence_dir/baseline/verifier-helper-manifests"
  mkdir -p "$helper_dir" || return 1
  render_verifier_helper_manifest \
    "$observer_manifest" \
    "$helper_dir/matching-event-observer.yaml" \
    "$retained_evidence_dir" || return 1

  observer_manifest="$helper_dir/matching-event-observer.yaml"
  matching_event_observer_manifest_prepared=true
}

verify_kind_loaded_verifier_image_identity() {
  local pod="$1"
  local retained_evidence_dir="$2"
  local image_transport expected_identity verifier_image_reference node actual_identity

  image_transport="$(
    simplematch_certification_image_transport "$retained_evidence_dir"
  )" || return 1
  [[ "$image_transport" == kind-load ]] || return 0

  command -v docker >/dev/null 2>&1 || return 1
  expected_identity="$(
    simplematch_certification_verifier_image_identity "$retained_evidence_dir"
  )" || return 1
  verifier_image_reference="$(
    simplematch_certification_verifier_image \
      "$repo_root" "$namespace" "$retained_evidence_dir"
  )" || return 1
  node="$(kns get pod "$pod" -o jsonpath='{.spec.nodeName}')" || return 1
  [[ -n "$node" ]] || return 1

  actual_identity="$(
    docker exec "$node" crictl inspecti "$verifier_image_reference" |
      jq -er '.status.id | select(type == "string" and test("^sha256:[0-9a-f]{64}$"))'
  )" || return 1
  [[ "$actual_identity" == "$expected_identity" ]]
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

create_kafka_observer_pod() {
  local retained_evidence_dir="$1"
  kns get pod "$kafka_observer_pod" >/dev/null 2>&1 &&
    die "Kafka observation Pod already exists: $kafka_observer_pod"
  kns create -f "$kafka_observer_manifest" >/dev/null ||
    die 'Kafka observation Pod could not be created'
  kafka_observer_created=true

  if ! kns wait --for=condition=Ready "pod/$kafka_observer_pod" \
      --timeout="${timeout_seconds}s" >/dev/null; then
    capture_kafka_observer_startup_diagnostics
    die 'Kafka observation Pod did not become Ready; inspect diagnostics/kafka-observer-startup'
  fi
  verify_kind_loaded_verifier_image_identity \
    "$kafka_observer_pod" "$retained_evidence_dir" ||
    die 'Kafka observation Pod does not use the retained kind-loaded verifier image'
}

start_kafka_observation_adapter() {
  local retained_evidence_dir="${1:-$(simplematch_production_like_evidence_dir "$repo_root")}"
  prepare_kafka_observer_manifest "$retained_evidence_dir" ||
    die 'retained production-like source or verifier image provenance is not valid'
  if [[ -n "${observer_manifest:-}" ]]; then
    prepare_matching_event_observer_manifest "$retained_evidence_dir" ||
      die 'Matching Event observer provenance is not valid'
  fi
  create_kafka_observer_pod "$retained_evidence_dir"

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

delete_kafka_observer_pod() {
  [[ "${kafka_observer_created:-false}" == true ]] || return 0
  kns delete pod "$kafka_observer_pod" --ignore-not-found \
    --wait=true --timeout="${timeout_seconds}s" >/dev/null 2>&1 || return 1
  kafka_observer_created=false
}

kafka_observation_request() {
  local path="$1"
  local destination="$2"
  local request_body="${3:-}"
  local status
  if [[ -n "$request_body" ]]; then
    status="$(
      curl --connect-timeout 2 --max-time 4 -sS \
        -H 'Content-Type: application/json' --data-binary "@$request_body" \
        -o "$destination" -w '%{http_code}' \
        -X POST "http://127.0.0.1:${kafka_observer_port}${path}"
    )" || return 1
  else
    status="$(
      curl --connect-timeout 2 --max-time 4 -sS \
        -o "$destination" -w '%{http_code}' \
        -X POST "http://127.0.0.1:${kafka_observer_port}${path}"
    )" || return 1
  fi
  [[ "$status" == 200 ]]
}

capture_kafka_close_barriers() {
  local before="$1"
  local after="$2"
  local destination="$3"
  local request="${destination%.json}-request.json"

  jq -e -n \
    --arg tradingSessionId "$trading_session_id" \
    --arg tradingDay "$trading_day" \
    --arg artifactContentSha256 "$artifact_checksum" \
    --arg routingAlgorithmVersion "$routing_algorithm_version" \
    --slurpfile before "$before" \
    --slurpfile after "$after" '
      {
        tradingSessionId:$tradingSessionId,
        tradingDay:$tradingDay,
        artifactContentSha256:$artifactContentSha256,
        routingAlgorithmVersion:$routingAlgorithmVersion,
        before:$before[0],
        after:$after[0]
      }
    ' >"$request" || return 1
  kafka_observation_request /close-barriers "$destination" "$request" || return 1
  jq -e '
    .topic == "matching.commands"
    and (.records | length) == 15
    and ([.records[].partition] == [range(0; 15)])
    and all(.records[]; .offset >= 0 and (.commandId | length > 0))
  ' "$destination" >/dev/null
}

capture_kafka_log_end_positions() {
  local commands_destination="$1"
  local events_destination="$2"
  local response="${commands_destination%.json}-session-response.json"

  kafka_observation_request /log-end-positions "$response" || return 1
  jq -e '.matchingCommands' "$response" >"$commands_destination" || return 1
  jq -e '.matchingEvents' "$response" >"$events_destination" || return 1
}

capture_kafka_matching_commands_end_positions() {
  local destination="$1"
  local response="${destination%.json}-session-response.json"

  kafka_observation_request /log-end-positions "$response" || return 1
  jq -e '
    .matchingCommands
    | select(.topic == "matching.commands")
    | select((.partitions | length) == 15)
    | select([.partitions[].partition] == [range(0; 15)])
  ' "$response" >"$destination"
}

capture_kafka_matching_events_end_positions() {
  local destination="$1"
  local response="${destination%.json}-session-response.json"

  kafka_observation_request /log-end-positions "$response" || return 1
  jq -e '
    .matchingEvents
    | select(.topic == "matching.events")
    | select((.partitions | length) == 15)
    | select([.partitions[].partition] == [range(0; 15)])
  ' "$response" >"$destination"
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

#!/usr/bin/env bash

# Local production-like Kafka and Compose helpers.
# Sourced by run-local-production-like-certification.sh; shared run state is owned
# by the top-level orchestrator. This file defines behavior only and has no entry point.

_certification_kafka_producer_payload() {
  local object_path="$1"

  jq -er '
    [.outputs[] |
      select(.kind == "file-content" and .name == "matching-producer-config")
    ] as $matches |
    if ($matches | length) == 1 then
      $matches[0]
    else
      error("matching producer configuration output mismatch")
    end
  ' "$object_path"
}

_certification_kafka_producer_payload_valid() {
  local object_path="$1"
  local payload identity content_base64 temp_file actual_identity

  payload="$(_certification_kafka_producer_payload "$object_path")" || return 1
  identity="$(jq -r '.identity' <<<"$payload")" || return 1
  content_base64="$(jq -r '.contentBase64 // empty' <<<"$payload")" || return 1
  [[ "$identity" =~ ^sha256:[0-9a-f]{64}$ && -n "$content_base64" ]] || return 1

  temp_file="$(mktemp)" || return 1
  if ! printf '%s' "$content_base64" | base64 --decode >"$temp_file" 2>/dev/null; then
    rm -f -- "$temp_file"
    return 1
  fi
  actual_identity="$(sha256sum "$temp_file" | awk '{print "sha256:" $1}')" || {
    rm -f -- "$temp_file"
    return 1
  }
  rm -f -- "$temp_file"
  [[ "$actual_identity" == "$identity" ]]
}

certification_kafka_phase_outputs_json() {
  local phase_id="$1"
  local identity content_base64

  case "$phase_id" in
    kafka-producer-contract)
      [[ -f "$matching_producer_config_file" ]] || return 1
      identity="$(sha256sum "$matching_producer_config_file" |
        awk '{print "sha256:" $1}')" || return 1
      content_base64="$(base64 <"$matching_producer_config_file" | tr -d '\n')" || \
        return 1
      [[ -n "$content_base64" ]] || return 1
      jq -cn \
        --arg identity "$identity" \
        --arg contentBase64 "$content_base64" '[{
          kind: "file-content",
          name: "matching-producer-config",
          identity: $identity,
          location: "matching-producer.config.txt",
          contentBase64: $contentBase64
        }]'
      ;;
    *)
      printf '%s\n' '[]'
      ;;
  esac
}

certification_kafka_phase_cached_outputs_valid() {
  local phase_id="$1"
  local evidence_digest="$2"
  local object_path

  case "$phase_id" in
    kafka-producer-contract)
      object_path="$(_certification_evidence_object_path "$evidence_digest")" || return 1
      certification_evidence_validate_object \
        "$object_path" "$evidence_digest" || return 1
      _certification_kafka_producer_payload_valid "$object_path"
      ;;
    *)
      return 0
      ;;
  esac
}

certification_kafka_phase_current_outputs_valid() {
  local phase_id="$1"
  local result_path="$2"
  local payload identity actual_identity

  case "$phase_id" in
    kafka-producer-contract)
      [[ -f "$result_path" && -f "$matching_producer_config_file" ]] || return 1
      payload="$(_certification_kafka_producer_payload "$result_path")" || return 1
      identity="$(jq -r '.identity' <<<"$payload")" || return 1
      [[ "$identity" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
      actual_identity="$(sha256sum "$matching_producer_config_file" |
        awk '{print "sha256:" $1}')" || return 1
      [[ "$actual_identity" == "$identity" ]]
      ;;
    *)
      return 0
      ;;
  esac
}

certification_kafka_phase_materialize_reused_outputs() {
  local phase_id="$1"
  local evidence_digest="$2"
  local object_path payload content_base64 identity temp_file actual_identity

  case "$phase_id" in
    kafka-producer-contract)
      object_path="$(_certification_evidence_object_path "$evidence_digest")" || return 1
      certification_evidence_validate_object \
        "$object_path" "$evidence_digest" || return 1
      _certification_kafka_producer_payload_valid "$object_path" || return 1
      payload="$(_certification_kafka_producer_payload "$object_path")" || return 1
      content_base64="$(jq -r '.contentBase64' <<<"$payload")" || return 1
      identity="$(jq -r '.identity' <<<"$payload")" || return 1

      mkdir -p "$(dirname -- "$matching_producer_config_file")" || return 1
      temp_file="$(mktemp "${matching_producer_config_file}.tmp.XXXXXX")" || return 1
      if ! printf '%s' "$content_base64" | base64 --decode >"$temp_file" 2>/dev/null; then
        rm -f -- "$temp_file"
        return 1
      fi
      actual_identity="$(sha256sum "$temp_file" |
        awk '{print "sha256:" $1}')" || {
        rm -f -- "$temp_file"
        return 1
      }
      [[ "$actual_identity" == "$identity" ]] || {
        rm -f -- "$temp_file"
        return 1
      }
      mv -f -- "$temp_file" "$matching_producer_config_file" || {
        rm -f -- "$temp_file"
        return 1
      }
      ;;
    *)
      return 0
      ;;
  esac
}

wait_for_compose() {
  local services service container_id state health ready attempt
  mapfile -t services < <("${compose_command[@]}" config --services)
  for attempt in $(seq 1 90); do
    check_certification_deadline
    ready=true
    for service in "${services[@]}"; do
      container_id="$("${compose_command[@]}" ps -q "$service")"
      if [[ -z "$container_id" ]]; then
        ready=false
        printf '%s is not created yet\n' "$service"
        continue
      fi
      state="$(docker inspect --format '{{.State.Status}}' "$container_id")"
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id")"
      printf '%s state=%s health=%s\n' "$service" "$state" "$health"
      if [[ "$state" != running || ( "$health" != healthy && "$health" != none ) ]]; then
        ready=false
      fi
    done
    if [[ "$ready" == true ]]; then
      printf '%s\n' 'All production-like Compose services are ready.'
      return 0
    fi
    sleep 2
  done
  die 'Production-like Compose services did not become ready within 180 seconds.'
}

create_kafka_topics() {
  local topic
  for topic in matching.commands matching.events account.lifecycle marketdata.events; do
    run_logged "kafka-create-${topic//./-}" "${compose_command[@]}" exec -T kafka-1 \
      /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:29092 --create --if-not-exists \
      --topic "$topic" --partitions 15 --replication-factor 3 \
      --config cleanup.policy=delete --config retention.ms=2592000000 --config min.insync.replicas=2
  done
}

generate_kafka_capacity_evidence() {
  local source_file="$matching_capacity_workload_file"
  local output_file="$matching_capacity_evidence_file"
  local service free_kib free_bytes
  local minimum_free_bytes=""
  local broker_count=3

  [[ -f "${source_file}" ]] || die "Kafka workload scenario does not exist: ${source_file}"
  if [[ -n "${SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE:-}" ]]; then
    [[ -f "${output_file}" ]] || die "Kafka capacity evidence does not exist: ${output_file}"
    printf 'Using supplied Kafka capacity evidence: %s\n' "${output_file}"
    return 0
  fi

  mkdir -p "$(dirname -- "${output_file}")"
  for service in kafka-1 kafka-2 kafka-3; do
    free_kib="$("${compose_command[@]}" exec -T "${service}" sh -c \
      'df -Pk / | tail -n 1' | awk 'NR == 1 { print $4 }' | tr -d '\r')"
    [[ "${free_kib}" =~ ^[0-9]+$ ]] || die \
      "Kafka broker ${service} reported invalid free filesystem blocks: ${free_kib}"
    free_bytes="$(awk -v kib="${free_kib}" 'BEGIN { printf "%.0f", kib * 1024 }')"
    if [[ -z "${minimum_free_bytes}" || "${free_bytes}" -lt "${minimum_free_bytes}" ]]; then
      minimum_free_bytes="${free_bytes}"
    fi
  done

  {
    awk -F= '/^(workload\.commands\.per\.day|workload\.events\.per\.day|workload\.average\.command\.record\.bytes|workload\.average\.event\.record\.bytes)=/ { print }' \
      "${source_file}"
    printf '%s\n' "capacity.broker.count=${broker_count}"
    printf '%s\n' "capacity.usable.cluster.bytes=${minimum_free_bytes}"
    printf '%s\n' "capacity.usable.broker.bytes=$((minimum_free_bytes / broker_count))"
    printf '%s\n' 'capacity.evidence.source=runtime-docker-filesystem'
    printf '%s\n' 'capacity.evidence.path=/'
    printf '%s\n' "capacity.evidence.generated_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >"${output_file}"
  printf 'Generated runtime Kafka capacity evidence: %s\n' "${output_file}"
}

collect_kafka_fixture() {
  local fixture_dir="$evidence_dir/kafka-fixture"
  local topic
  mkdir -p "$fixture_dir"
  for topic in matching.commands matching.events; do
    run_capture "kafka-describe-${topic//./-}" "$fixture_dir/${topic}.topic.txt" \
      "${compose_command[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server kafka-1:29092 --describe --topic "$topic"
    run_capture "kafka-config-${topic//./-}" "$fixture_dir/${topic}.config.txt" \
      "${compose_command[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-configs.sh \
      --bootstrap-server kafka-1:29092 --entity-type topics --entity-name "$topic" --describe
  done
  run_capture kafka-broker-config "$fixture_dir/broker.config.txt" \
    "${compose_command[@]}" exec -T kafka-1 cat /opt/kafka/config/server.properties
  run_logged kafka-profile-validation bash "$repo_root/scripts/validate-matching-topic-profile.sh" \
    --profile production --fixture-dir "$fixture_dir" \
    --producer-config-file "$matching_producer_config_file" \
    --capacity-evidence-file "$matching_capacity_evidence_file" --certify-production
}

#!/usr/bin/env bash

MATCHING_PROFILE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../config/kafka" && pwd)"
MATCHING_KAFKA_BIN_DIR=""
PROFILE_FILE=""
PROFILE_NAME=""

matching_die() {
  printf '%s\n' "$*" >&2
  exit 1
}

matching_profile_path() {
  case "$1" in
    production) printf '%s\n' "${MATCHING_PROFILE_DIR}/matching-production.properties" ;;
    local) printf '%s\n' "${MATCHING_PROFILE_DIR}/matching-local.properties" ;;
    *) matching_die "Unknown Matching Kafka profile: $1" ;;
  esac
}

matching_profile_value() {
  local key="$1"
  local value
  value="$(matching_file_value "${PROFILE_FILE}" "${key}")" ||
    matching_die "Missing ${key} in ${PROFILE_FILE}"
  printf '%s\n' "${value}"
}

matching_file_value() {
  local file="$1"
  local key="$2"
  awk -F= -v requested_key="${key}" '
    /^[[:space:]]*#/ { next }
    {
      candidate = $1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", candidate)
      if (candidate == requested_key) {
        value = substr($0, index($0, "=") + 1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        if (value == "") exit 2
        print value
        found = 1
        exit
      }
    }
    END {
      if (!found) exit 1
    }
  ' "${file}"
}

matching_assert_profile_value() {
  local key="$1"
  local expected="$2"
  local actual
  actual="$(matching_profile_value "${key}")"
  [[ "${actual}" == "${expected}" ]] || matching_die \
    "${PROFILE_NAME} profile ${key}: expected ${expected}, got ${actual}"
}

matching_load_profile() {
  PROFILE_NAME="$1"
  PROFILE_FILE="$(matching_profile_path "${PROFILE_NAME}")"
  [[ -f "${PROFILE_FILE}" ]] || matching_die "Profile does not exist: ${PROFILE_FILE}"

  matching_assert_profile_value profile.name "${PROFILE_NAME}"
  matching_assert_profile_value topic.partition.count 15
  matching_assert_profile_value topic.cleanup.policy delete
  matching_assert_profile_value topic.retention.ms 2592000000
  matching_assert_profile_value capacity.retention.days 30
  matching_assert_profile_value capacity.headroom.percent 30
  matching_assert_profile_value broker.auto.create.topics.enable false
  matching_assert_profile_value broker.unclean.leader.election.enable false
  matching_assert_profile_value producer.acks all
  matching_assert_profile_value producer.enable.idempotence true

  case "${PROFILE_NAME}" in
    production)
      matching_assert_profile_value certifies.production true
      matching_assert_profile_value topic.replication.factor 3
      matching_assert_profile_value topic.min.insync.replicas 2
      ;;
    local)
      matching_assert_profile_value certifies.production false
      matching_assert_profile_value topic.replication.factor 1
      matching_assert_profile_value topic.min.insync.replicas 1
      ;;
  esac
}

matching_require_file() {
  local file="$1"
  local description="$2"
  [[ -f "${file}" ]] || matching_die "${description} does not exist: ${file}"
}

matching_require_integer() {
  local value="$1"
  local description="$2"
  [[ "${value}" =~ ^[0-9]+$ ]] || matching_die \
    "${description} must be a non-negative integer, got ${value}"
}

matching_require_positive_integer() {
  local value="$1"
  local description="$2"
  matching_require_integer "${value}" "${description}"
  [[ "${value}" != 0 ]] || matching_die "${description} must be positive"
}

matching_validate_producer_config() {
  local producer_config_file="$1"
  local acks
  local idempotence
  matching_require_file "${producer_config_file}" 'Producer configuration'
  acks="$(matching_file_value "${producer_config_file}" acks)" ||
    matching_die "Producer configuration is missing acks: ${producer_config_file}"
  idempotence="$(matching_file_value "${producer_config_file}" enable.idempotence)" ||
    matching_die \
      "Producer configuration is missing enable.idempotence: ${producer_config_file}"
  [[ "${acks}" == all ]] || matching_die \
    "Producer configuration acks must be all, got ${acks}"
  [[ "${idempotence}" == true ]] || matching_die \
    "Producer configuration enable.idempotence must be true, got ${idempotence}"
}

matching_validate_capacity_evidence() {
  local capacity_evidence_file="$1"
  local commands_per_day
  local events_per_day
  local command_record_bytes
  local event_record_bytes
  local broker_count
  local usable_cluster_bytes
  local usable_broker_bytes
  local retention_days
  local headroom_percent
  local required_cluster_bytes
  local required_broker_bytes

  matching_require_file "${capacity_evidence_file}" 'Capacity evidence'
  commands_per_day="$(matching_file_value "${capacity_evidence_file}" \
    workload.commands.per.day)" || matching_die \
    "Capacity evidence is missing workload.commands.per.day: ${capacity_evidence_file}"
  events_per_day="$(matching_file_value "${capacity_evidence_file}" \
    workload.events.per.day)" || matching_die \
    "Capacity evidence is missing workload.events.per.day: ${capacity_evidence_file}"
  command_record_bytes="$(matching_file_value "${capacity_evidence_file}" \
    workload.average.command.record.bytes)" || matching_die \
    "Capacity evidence is missing workload.average.command.record.bytes: ${capacity_evidence_file}"
  event_record_bytes="$(matching_file_value "${capacity_evidence_file}" \
    workload.average.event.record.bytes)" || matching_die \
    "Capacity evidence is missing workload.average.event.record.bytes: ${capacity_evidence_file}"
  broker_count="$(matching_file_value "${capacity_evidence_file}" capacity.broker.count)" || \
    matching_die "Capacity evidence is missing capacity.broker.count: ${capacity_evidence_file}"
  usable_cluster_bytes="$(matching_file_value "${capacity_evidence_file}" \
    capacity.usable.cluster.bytes)" || matching_die \
    "Capacity evidence is missing capacity.usable.cluster.bytes: ${capacity_evidence_file}"
  usable_broker_bytes="$(matching_file_value "${capacity_evidence_file}" \
    capacity.usable.broker.bytes)" || matching_die \
    "Capacity evidence is missing capacity.usable.broker.bytes: ${capacity_evidence_file}"
  retention_days="$(matching_profile_value capacity.retention.days)"
  headroom_percent="$(matching_profile_value capacity.headroom.percent)"

  matching_require_positive_integer "${commands_per_day}" 'workload.commands.per.day'
  matching_require_positive_integer "${events_per_day}" 'workload.events.per.day'
  matching_require_positive_integer "${command_record_bytes}" \
    'workload.average.command.record.bytes'
  matching_require_positive_integer "${event_record_bytes}" \
    'workload.average.event.record.bytes'
  matching_require_positive_integer "${broker_count}" 'capacity.broker.count'
  matching_require_positive_integer "${usable_cluster_bytes}" \
    'capacity.usable.cluster.bytes'
  matching_require_positive_integer "${usable_broker_bytes}" \
    'capacity.usable.broker.bytes'
  matching_require_positive_integer "${retention_days}" 'profile capacity.retention.days'
  matching_require_integer "${headroom_percent}" 'profile capacity.headroom.percent'
  (( headroom_percent > 0 && headroom_percent < 100 )) || matching_die \
    'profile capacity.headroom.percent must be between 1 and 99'
  [[ "${broker_count}" == "$(matching_profile_value topic.replication.factor)" ]] || \
    matching_die \
      "Capacity evidence broker count ${broker_count} must equal topic replication factor " \
      "$(matching_profile_value topic.replication.factor)"

  read -r required_cluster_bytes required_broker_bytes < <(
    matching_capacity_requirements \
      "${commands_per_day}" "${events_per_day}" "${command_record_bytes}" \
      "${event_record_bytes}" "${retention_days}" \
      "$(matching_profile_value topic.replication.factor)" "${headroom_percent}"
  )

  [[ "${usable_cluster_bytes}" -ge "${required_cluster_bytes}" ]] || matching_die \
    "Capacity evidence is below the required 30-day replicated headroom: " \
    "required=${required_cluster_bytes}, usable=${usable_cluster_bytes}"
  [[ "${usable_broker_bytes}" -ge "${required_broker_bytes}" ]] || matching_die \
    "Capacity evidence is below the required per-broker headroom: " \
    "required=${required_broker_bytes}, usable=${usable_broker_bytes}"

  printf '%s\n' \
    "Matching Kafka capacity evidence is valid (required cluster bytes: ${required_cluster_bytes}; " \
    "required broker bytes: ${required_broker_bytes})."
}

matching_capacity_requirements() {
  local commands_per_day="$1"
  local events_per_day="$2"
  local command_record_bytes="$3"
  local event_record_bytes="$4"
  local retention_days="$5"
  local replication_factor="$6"
  local headroom_percent="$7"
  awk -v commands_per_day="${commands_per_day}" -v events_per_day="${events_per_day}" \
    -v command_record_bytes="${command_record_bytes}" -v event_record_bytes="${event_record_bytes}" \
    -v retention_days="${retention_days}" -v replication_factor="${replication_factor}" \
    -v headroom_percent="${headroom_percent}" 'BEGIN {
      logical_bytes = commands_per_day * command_record_bytes + events_per_day * event_record_bytes;
      denominator = 100 - headroom_percent;
      cluster_numerator = logical_bytes * retention_days * replication_factor * 100;
      broker_numerator = logical_bytes * retention_days * 100;
      printf "%.0f %.0f\n", int((cluster_numerator + denominator - 1) / denominator),
        int((broker_numerator + denominator - 1) / denominator);
    }'
}

matching_require_certification_evidence() {
  local producer_config_file="$1"
  local capacity_evidence_file="$2"
  [[ -n "${producer_config_file}" ]] || matching_die \
    '--certify-production requires --producer-config-file'
  [[ -n "${capacity_evidence_file}" ]] || matching_die \
    '--certify-production requires --capacity-evidence-file'
  matching_validate_producer_config "${producer_config_file}"
  matching_validate_capacity_evidence "${capacity_evidence_file}"
}

matching_require_production_profile() {
  [[ "${PROFILE_NAME}" == "production" ]] || matching_die \
    "The local Matching Kafka profile cannot pass the local RF3 durability gate"
  matching_assert_profile_value certifies.production true
}

matching_find_kafka_command() {
  local command_name="$1"
  local candidate
  if [[ -n "${MATCHING_KAFKA_BIN_DIR}" ]]; then
    for candidate in "${MATCHING_KAFKA_BIN_DIR}/${command_name}.sh" \
      "${MATCHING_KAFKA_BIN_DIR}/${command_name}"; do
      if [[ -x "${candidate}" ]]; then
        printf '%s\n' "${candidate}"
        return
      fi
    done
    matching_die "Cannot find ${command_name} in ${MATCHING_KAFKA_BIN_DIR}"
  fi

  for candidate in "${command_name}.sh" "${command_name}"; do
    if command -v "${candidate}" >/dev/null 2>&1; then
      command -v "${candidate}"
      return
    fi
  done
  matching_die "Cannot find ${command_name}; set --kafka-bin-dir if needed"
}

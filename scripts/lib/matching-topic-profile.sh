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
  value="$(awk -F= -v requested_key="${key}" '
    $1 == requested_key { print substr($0, length(requested_key) + 2); found = 1 }
    END { if (!found) exit 1 }
  ' "${PROFILE_FILE}")" || matching_die "Missing ${key} in ${PROFILE_FILE}"
  [[ -n "${value}" ]] || matching_die "Empty ${key} in ${PROFILE_FILE}"
  printf '%s\n' "${value}"
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

matching_require_production_profile() {
  [[ "${PROFILE_NAME}" == "production" ]] || matching_die \
    "The local Matching Kafka profile cannot pass production certification"
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

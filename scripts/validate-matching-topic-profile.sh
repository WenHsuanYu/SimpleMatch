#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/matching-topic-profile.sh
source "${SCRIPT_DIR}/lib/matching-topic-profile.sh"

BOOTSTRAP_SERVER=""
PROFILE="production"
FIXTURE_DIR=""
BROKER_CONFIG_FILE=""
CERTIFY_PRODUCTION=false
COMMAND_CONFIG_FILE="${MATCHING_KAFKA_COMMAND_CONFIG:-}"

usage() {
  printf '%s\n' \
    'Usage: validate-matching-topic-profile.sh [options]' \
    '' \
    '  --bootstrap-server HOST:PORT  Query a Kafka cluster.' \
    '  --fixture-dir DIRECTORY       Validate saved Kafka CLI output instead.' \
    '  --broker-config-file FILE     Use the effective broker config instead of a live query.' \
    '  --command-config FILE        Kafka CLI TLS/SASL client properties.' \
    '  --profile production|local    Select the profile (default: production).' \
    '  --certify-production          Reject any non-production profile.' \
    '  --kafka-bin-dir DIRECTORY     Directory containing Kafka CLI programs.'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bootstrap-server) BOOTSTRAP_SERVER="$2"; shift 2 ;;
    --fixture-dir) FIXTURE_DIR="$2"; shift 2 ;;
    --broker-config-file) BROKER_CONFIG_FILE="$2"; shift 2 ;;
    --command-config) COMMAND_CONFIG_FILE="$2"; shift 2 ;;
    --profile) PROFILE="$2"; shift 2 ;;
    --certify-production) CERTIFY_PRODUCTION=true; shift ;;
    --kafka-bin-dir) MATCHING_KAFKA_BIN_DIR="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; matching_die "Unknown option: $1" ;;
  esac
done

[[ -n "${FIXTURE_DIR}" || -n "${BOOTSTRAP_SERVER}" ]] || matching_die \
  'Specify --fixture-dir or --bootstrap-server'
[[ -z "${FIXTURE_DIR}" || -z "${BOOTSTRAP_SERVER}" ]] || matching_die \
  'Use either --fixture-dir or --bootstrap-server, not both'
if [[ -n "${COMMAND_CONFIG_FILE}" ]]; then
  [[ -f "${COMMAND_CONFIG_FILE}" ]] || matching_die \
    "Kafka command config does not exist: ${COMMAND_CONFIG_FILE}"
fi

KAFKA_COMMAND_ARGS=()
if [[ -n "${COMMAND_CONFIG_FILE}" ]]; then
  KAFKA_COMMAND_ARGS+=(--command-config "${COMMAND_CONFIG_FILE}")
fi

matching_load_profile "${PROFILE}"
if [[ "${CERTIFY_PRODUCTION}" == true ]]; then
  matching_require_production_profile
fi

config_value() {
  local config_file="$1"
  local requested_key="$2"
  awk -v key="${requested_key}" '
    {
      field_count = split($0, fields, /[[:space:],]+/)
      for (field_index = 1; field_index <= field_count; field_index++) {
        separator = index(fields[field_index], "=")
        if (separator > 1 && substr(fields[field_index], 1, separator - 1) == key) {
          print substr(fields[field_index], separator + 1)
          exit
        }
      }
    }
  ' "${config_file}"
}

assert_config_value() {
  local config_file="$1"
  local key="$2"
  local expected="$3"
  local description="$4"
  local actual
  actual="$(config_value "${config_file}" "${key}")"
  [[ "${actual}" == "${expected}" ]] || matching_die \
    "${description}: expected ${key}=${expected}, got ${actual:-missing}"
}

assert_topic_shape() {
  local topic="$1"
  local topic_file="$2"
  local expected_partitions
  local expected_replication
  local partition_lines
  local under_replicated
  local replica_ids
  local replica_count
  expected_partitions="$(matching_profile_value topic.partition.count)"
  expected_replication="$(matching_profile_value topic.replication.factor)"

  grep -Eq "PartitionCount:[[:space:]]*${expected_partitions}([^0-9]|$)" "${topic_file}" || \
    matching_die "${topic}: partition count is not ${expected_partitions}"
  grep -Eq "ReplicationFactor:[[:space:]]*${expected_replication}([^0-9]|$)" "${topic_file}" || \
    matching_die "${topic}: replication factor is not ${expected_replication}"
  partition_lines="$(grep -Ec 'Partition:[[:space:]]*[0-9]+' "${topic_file}" || true)"
  [[ "${partition_lines}" == "${expected_partitions}" ]] || matching_die \
    "${topic}: expected ${expected_partitions} partition descriptions, got ${partition_lines}"

  replica_ids="$(awk '
    /Partition:/ {
      replicas = $0
      sub(/^.*Replicas:[[:space:]]*/, "", replicas)
      sub(/[[:space:]]+Isr:.*$/, "", replicas)
      gsub(/,/, "\n", replicas)
      print replicas
    }
  ' "${topic_file}" | LC_ALL=C sort -u)"
  replica_count="$(printf '%s\n' "${replica_ids}" | awk 'NF { count++ } END { print count + 0 }')"
  [[ "${replica_count}" == "${expected_replication}" ]] || matching_die \
    "${topic}: expected ${expected_replication} distinct replica broker IDs, got ${replica_count}"

  under_replicated="$(awk -v minimum="$(matching_profile_value topic.min.insync.replicas)" '
    /Partition:/ {
      isr = $0
      sub(/^.*Isr:[[:space:]]*/, "", isr)
      sub(/[[:space:]].*$/, "", isr)
      count = split(isr, replicas, ",")
      if (count < minimum) {
        print $0
        exit
      }
    }
  ' "${topic_file}")"
  [[ -z "${under_replicated}" ]] || matching_die \
    "${topic}: an assigned partition has fewer than the required ISR: ${under_replicated}"
}

assert_topic_config() {
  local topic="$1"
  local config_file="$2"
  assert_config_value "${config_file}" cleanup.policy \
    "$(matching_profile_value topic.cleanup.policy)" "${topic} cleanup policy"
  assert_config_value "${config_file}" retention.ms \
    "$(matching_profile_value topic.retention.ms)" "${topic} retention"
  assert_config_value "${config_file}" min.insync.replicas \
    "$(matching_profile_value topic.min.insync.replicas)" "${topic} minimum ISR"
}

OUTPUT_DIR="${FIXTURE_DIR}"
TEMPORARY_OUTPUT_DIR=""
if [[ -z "${FIXTURE_DIR}" ]]; then
  TEMPORARY_OUTPUT_DIR="$(mktemp -d)"
  OUTPUT_DIR="${TEMPORARY_OUTPUT_DIR}"
  trap 'rm -rf "${TEMPORARY_OUTPUT_DIR}"' EXIT
  KAFKA_TOPICS="$(matching_find_kafka_command kafka-topics)"
  KAFKA_CONFIGS="$(matching_find_kafka_command kafka-configs)"
  for topic in matching.commands matching.events; do
    "${KAFKA_TOPICS}" "${KAFKA_COMMAND_ARGS[@]}" --bootstrap-server "${BOOTSTRAP_SERVER}" --describe --topic "${topic}" \
      > "${OUTPUT_DIR}/${topic}.topic.txt"
    "${KAFKA_CONFIGS}" "${KAFKA_COMMAND_ARGS[@]}" --bootstrap-server "${BOOTSTRAP_SERVER}" --entity-type topics \
      --entity-name "${topic}" --describe > "${OUTPUT_DIR}/${topic}.config.txt"
  done
  if [[ -n "${BROKER_CONFIG_FILE}" ]]; then
    cp "${BROKER_CONFIG_FILE}" "${OUTPUT_DIR}/broker.config.txt"
  else
    "${KAFKA_CONFIGS}" "${KAFKA_COMMAND_ARGS[@]}" --bootstrap-server "${BOOTSTRAP_SERVER}" --entity-type brokers \
      --entity-default --describe > "${OUTPUT_DIR}/broker.config.txt"
  fi
fi

for topic in matching.commands matching.events; do
  assert_topic_shape "${topic}" "${OUTPUT_DIR}/${topic}.topic.txt"
  assert_topic_config "${topic}" "${OUTPUT_DIR}/${topic}.config.txt"
done
assert_config_value "${OUTPUT_DIR}/broker.config.txt" auto.create.topics.enable \
  "$(matching_profile_value broker.auto.create.topics.enable)" 'automatic topic creation policy'
assert_config_value "${OUTPUT_DIR}/broker.config.txt" unclean.leader.election.enable \
  "$(matching_profile_value broker.unclean.leader.election.enable)" 'unclean leader election policy'

printf 'Matching Kafka %s profile is valid.\n' "${PROFILE_NAME}"

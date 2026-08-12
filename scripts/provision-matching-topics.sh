#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/matching-topic-profile.sh
source "${SCRIPT_DIR}/lib/matching-topic-profile.sh"

BOOTSTRAP_SERVER=""
PROFILE="production"
BROKER_CONFIG_FILE=""
PRODUCER_CONFIG_FILE=""
CAPACITY_EVIDENCE_FILE=""
CERTIFY_PRODUCTION=false
DRY_RUN=false
COMMAND_CONFIG_FILE="${MATCHING_KAFKA_COMMAND_CONFIG:-}"

usage() {
  printf '%s\n' \
    'Usage: provision-matching-topics.sh --bootstrap-server HOST:PORT [options]' \
    '' \
    '  --profile production|local    Select the profile (default: production).' \
    '  --broker-config-file FILE     Effective broker configuration for validation.' \
    '  --command-config FILE        Kafka CLI TLS/SASL client properties.' \
    '  --producer-config-file FILE  Effective Matching producer properties.' \
    '  --capacity-evidence-file FILE Workload-based retention and disk evidence.' \
    '  --certify-production          Validate the production profile after provisioning.' \
    '  --dry-run                     Print Kafka CLI commands without changing a broker.' \
    '  --kafka-bin-dir DIRECTORY     Directory containing Kafka CLI programs.'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bootstrap-server) BOOTSTRAP_SERVER="$2"; shift 2 ;;
    --profile) PROFILE="$2"; shift 2 ;;
    --broker-config-file) BROKER_CONFIG_FILE="$2"; shift 2 ;;
    --command-config) COMMAND_CONFIG_FILE="$2"; shift 2 ;;
    --producer-config-file) PRODUCER_CONFIG_FILE="$2"; shift 2 ;;
    --capacity-evidence-file) CAPACITY_EVIDENCE_FILE="$2"; shift 2 ;;
    --certify-production) CERTIFY_PRODUCTION=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    --kafka-bin-dir) MATCHING_KAFKA_BIN_DIR="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; matching_die "Unknown option: $1" ;;
  esac
done

[[ -n "${BOOTSTRAP_SERVER}" ]] || matching_die 'Specify --bootstrap-server'
matching_load_profile "${PROFILE}"
if [[ "${CERTIFY_PRODUCTION}" == true ]]; then
  matching_require_production_profile
fi
if [[ -n "${COMMAND_CONFIG_FILE}" ]]; then
  matching_require_file "${COMMAND_CONFIG_FILE}" 'Kafka command config'
fi
if [[ -n "${PRODUCER_CONFIG_FILE}" ]]; then
  matching_require_file "${PRODUCER_CONFIG_FILE}" 'Producer configuration'
fi
if [[ -n "${CAPACITY_EVIDENCE_FILE}" ]]; then
  matching_require_file "${CAPACITY_EVIDENCE_FILE}" 'Capacity evidence'
fi
if [[ -n "${PRODUCER_CONFIG_FILE}" ]]; then
  matching_validate_producer_config "${PRODUCER_CONFIG_FILE}"
fi
if [[ -n "${CAPACITY_EVIDENCE_FILE}" ]]; then
  matching_validate_capacity_evidence "${CAPACITY_EVIDENCE_FILE}"
fi

KAFKA_COMMAND_ARGS=()
if [[ -n "${COMMAND_CONFIG_FILE}" ]]; then
  KAFKA_COMMAND_ARGS+=(--command-config "${COMMAND_CONFIG_FILE}")
fi

run_command() {
  if [[ "${DRY_RUN}" == true ]]; then
    printf 'DRY RUN:'
    printf ' %q' "$@"
    printf '\n'
    return
  fi
  "$@"
}

if [[ "${DRY_RUN}" == false ]]; then
  KAFKA_TOPICS="$(matching_find_kafka_command kafka-topics)"
else
  KAFKA_TOPICS='kafka-topics'
fi

for topic in matching.commands matching.events; do
  run_command "${KAFKA_TOPICS}" "${KAFKA_COMMAND_ARGS[@]}" --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
    --topic "${topic}" --partitions "$(matching_profile_value topic.partition.count)" \
    --replication-factor "$(matching_profile_value topic.replication.factor)" \
    --config "cleanup.policy=$(matching_profile_value topic.cleanup.policy)" \
    --config "retention.ms=$(matching_profile_value topic.retention.ms)" \
    --config "min.insync.replicas=$(matching_profile_value topic.min.insync.replicas)"
done

if [[ "${DRY_RUN}" == false ]]; then
  validation_args=("${SCRIPT_DIR}/validate-matching-topic-profile.sh" \
    --bootstrap-server "${BOOTSTRAP_SERVER}" --profile "${PROFILE}")
  if [[ -n "${BROKER_CONFIG_FILE}" ]]; then
    validation_args+=(--broker-config-file "${BROKER_CONFIG_FILE}")
  fi
  if [[ -n "${COMMAND_CONFIG_FILE}" ]]; then
    validation_args+=(--command-config "${COMMAND_CONFIG_FILE}")
  fi
  if [[ -n "${PRODUCER_CONFIG_FILE}" ]]; then
    validation_args+=(--producer-config-file "${PRODUCER_CONFIG_FILE}")
  fi
  if [[ -n "${CAPACITY_EVIDENCE_FILE}" ]]; then
    validation_args+=(--capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}")
  fi
  if [[ "${CERTIFY_PRODUCTION}" == true ]]; then
    validation_args+=(--certify-production)
  fi
  if [[ -n "${MATCHING_KAFKA_BIN_DIR}" ]]; then
    validation_args+=(--kafka-bin-dir "${MATCHING_KAFKA_BIN_DIR}")
  fi
  "${validation_args[@]}"
fi

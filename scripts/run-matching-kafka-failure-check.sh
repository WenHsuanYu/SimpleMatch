#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/.." && pwd)"
compose_project="simplematch-local-production-like"
compose_file="${repo_root}/deploy/compose/kafka-connect.production-like.yml"
evidence_dir="${repo_root}/out/certification/local-production-like/kafka-failure"
producer_config_file=""
capacity_evidence_file=""
validator="${repo_root}/scripts/validate-matching-topic-profile.sh"
compose_command=()

usage() {
  printf '%s\n' \
    'Usage: run-matching-kafka-failure-check.sh [options]' \
    '' \
    '  --compose-project NAME          Compose project name.' \
    '  --compose-file FILE             Production-like Compose file.' \
    '  --evidence-dir DIRECTORY        Failure evidence output directory.' \
    '  --producer-config-file FILE     Producer evidence passed to the validator.' \
    '  --capacity-evidence-file FILE  Capacity evidence passed to the validator.'
}

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --compose-project) compose_project="${2:?--compose-project requires a value}"; shift 2 ;;
    --compose-file) compose_file="${2:?--compose-file requires a value}"; shift 2 ;;
    --evidence-dir) evidence_dir="${2:?--evidence-dir requires a value}"; shift 2 ;;
    --producer-config-file) producer_config_file="${2:?--producer-config-file requires a value}"; shift 2 ;;
    --capacity-evidence-file) capacity_evidence_file="${2:?--capacity-evidence-file requires a value}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "Unknown option: $1" ;;
  esac
done

[[ -f "${compose_file}" ]] || die "Compose file does not exist: ${compose_file}"
[[ -n "${producer_config_file}" ]] || die '--producer-config-file is required'
[[ -n "${capacity_evidence_file}" ]] || die '--capacity-evidence-file is required'
[[ -f "${producer_config_file}" ]] || die "Producer evidence does not exist: ${producer_config_file}"
[[ -f "${capacity_evidence_file}" ]] || die "Capacity evidence does not exist: ${capacity_evidence_file}"

compose_command=(docker compose --project-name "${compose_project}" --file "${compose_file}")
mkdir -p "${evidence_dir}"

restore_brokers() {
  set +e
  "${compose_command[@]}" start kafka-2 kafka-1 >/dev/null 2>&1
}
trap restore_brokers EXIT

wait_for_broker() {
  local service="$1"
  local container_id state health
  for _ in $(seq 1 60); do
    container_id="$("${compose_command[@]}" ps -q "${service}")"
    if [[ -n "${container_id}" ]]; then
      state="$(docker inspect --format '{{.State.Status}}' "${container_id}")"
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}")"
      if [[ "${state}" == running && ( "${health}" == healthy || "${health}" == none ) ]]; then
        return 0
      fi
    fi
    sleep 2
  done
  die "Kafka broker ${service} did not become healthy"
}

capture_fixture() {
  local scenario="$1"
  local bootstrap_service="$2"
  local scenario_dir="${evidence_dir}/${scenario}"
  local topic
  mkdir -p "${scenario_dir}"
  for topic in matching.commands matching.events; do
    "${compose_command[@]}" exec -T "${bootstrap_service}" \
      /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${bootstrap_service}:29092" \
      --describe --topic "${topic}" >"${scenario_dir}/${topic}.topic.txt" 2>&1 || return 1
    "${compose_command[@]}" exec -T "${bootstrap_service}" \
      /opt/kafka/bin/kafka-configs.sh --bootstrap-server "${bootstrap_service}:29092" \
      --entity-type topics --entity-name "${topic}" --describe \
      >"${scenario_dir}/${topic}.config.txt" 2>&1 || return 1
  done
  "${compose_command[@]}" exec -T "${bootstrap_service}" cat /opt/kafka/config/server.properties \
    >"${scenario_dir}/broker.config.txt" 2>&1 || return 1
}

validate_fixture() {
  local scenario="$1"
  local bootstrap_service="$2"
  local scenario_dir="${evidence_dir}/${scenario}"
  capture_fixture "${scenario}" "${bootstrap_service}" || return 1
  "${validator}" --profile production --fixture-dir "${scenario_dir}" \
    --producer-config-file "${producer_config_file}" \
    --capacity-evidence-file "${capacity_evidence_file}" --certify-production \
    >"${scenario_dir}/validation.log" 2>&1 || {
      cat "${scenario_dir}/validation.log" >&2
      return 1
    }
}

wait_for_broker kafka-1
wait_for_broker kafka-2
wait_for_broker kafka-3

"${compose_command[@]}" stop kafka-1 >/dev/null
wait_for_broker kafka-2
wait_for_broker kafka-3
validate_fixture one-broker-loss kafka-2 || die 'Live one-broker-loss validation failed'
printf '%s\n' 'Live one-broker-loss validation passed.' | tee \
  "${evidence_dir}/one-broker-loss.result.txt"

"${compose_command[@]}" stop kafka-2 >/dev/null
if validate_fixture two-broker-loss kafka-3; then
  die 'Live two-broker-loss state was accepted unexpectedly'
else
  printf '%s\n' 'Live two-broker-loss state rejected as expected.' | tee \
    "${evidence_dir}/two-broker-loss.result.txt"
fi

"${compose_command[@]}" start kafka-2 kafka-1 >/dev/null
wait_for_broker kafka-1
wait_for_broker kafka-2
wait_for_broker kafka-3
validate_fixture restored-cluster kafka-1 || die 'Kafka validation failed after broker restoration'
printf '%s\n' 'Kafka validation passed after broker restoration.' | tee \
  "${evidence_dir}/restored-cluster.result.txt"

trap - EXIT
printf '%s\n' 'Live Kafka broker failure checks passed.'

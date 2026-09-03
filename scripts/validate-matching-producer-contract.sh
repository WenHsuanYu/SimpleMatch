#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/.." && pwd)"
output_file=""

usage() {
  printf '%s\n' \
    'Usage: validate-matching-producer-contract.sh [--output FILE]' \
    '' \
    '  --output FILE  Write source-backed producer evidence to FILE.'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) output_file="${2:?--output requires a value}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

require_pattern() {
  local file="$1"
  local pattern="$2"
  local description="$3"
  grep -Eq "${pattern}" "${repo_root}/${file}" || {
    printf 'Missing %s in %s\n' "${description}" "${file}" >&2
    exit 1
  }
}

require_pattern services/risk-service/src/main/resources/application.yaml \
  'acks:[[:space:]]+all' 'Risk producer acknowledgements'
require_pattern services/risk-service/src/main/resources/application.yaml \
  'enable\.idempotence:[[:space:]]+true' 'Risk producer idempotence'
require_pattern services/quickfix-gateway/src/main/resources/application.yaml \
  'acks:[[:space:]]+all' 'QuickFIX producer acknowledgements'
require_pattern services/quickfix-gateway/src/main/resources/application.yaml \
  'enable\.idempotence:[[:space:]]+true' 'QuickFIX producer idempotence'
require_pattern services/market-data-projection/src/main/resources/application.yaml \
  'acks:[[:space:]]+all' 'Market-data projection acknowledgements'
require_pattern services/market-data-projection/src/main/resources/application.yaml \
  'enable\.idempotence:[[:space:]]+true' 'Market-data projection idempotence'
require_pattern matching-engine/src/rdkafka_runtime_adapter.cpp \
  '"enable\.idempotence", "true"' \
  'Matching event producer idempotence'
require_pattern matching-engine/src/rdkafka_runtime_adapter.cpp \
  '"acks", "all"' \
  'Matching event producer acknowledgements'
require_pattern matching-engine/tests/matching_kafka_fixture_publisher.cpp \
  '"enable\.idempotence", "true"' \
  'Matching fixture producer idempotence'
require_pattern matching-engine/tests/matching_kafka_fixture_publisher.cpp \
  '"acks", "all"' \
  'Matching fixture producer acknowledgements'
require_pattern matching-engine/tests/matching_kafka_fixture_publisher.cpp \
  'kFixtureHeaderName[[:space:]]*=[[:space:]]*"simplematch\.fixture"' \
  'explicit local fixture marker name'
require_pattern matching-engine/tests/matching_kafka_fixture_publisher.cpp \
  'kFixtureHeaderValue[[:space:]]*=[[:space:]]*"matching-kafka-fixture-v1"' \
  'explicit local fixture marker value'

if [[ -n "${output_file}" ]]; then
  mkdir -p "$(dirname -- "${output_file}")"
  {
    printf '%s\n' '# Source-backed Matching producer evidence.'
    printf '%s\n' 'acks=all'
    printf '%s\n' 'enable.idempotence=true'
    printf '%s\n' 'fixture.marker=matching-kafka-fixture-v1'
    printf '%s\n' 'evidence.source=repository-producer-config-and-code'
  } >"${output_file}"
fi

printf '%s\n' 'Matching producer contract is valid.'

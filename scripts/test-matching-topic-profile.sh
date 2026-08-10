#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="${SCRIPT_DIR}/testdata/matching-topic-profile/valid"
VALIDATOR="${SCRIPT_DIR}/validate-matching-topic-profile.sh"
PROVISIONER="${SCRIPT_DIR}/provision-matching-topics.sh"

assert_fails() {
  local description="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    printf '%s unexpectedly succeeded\n' "${description}" >&2
    exit 1
  fi
}

"${VALIDATOR}" --profile production --fixture-dir "${FIXTURE_DIR}" --certify-production
assert_fails 'local profile production certification' "${VALIDATOR}" --profile local \
  --fixture-dir "${FIXTURE_DIR}" --certify-production

TEMPORARY_FIXTURES="$(mktemp -d)"
trap 'rm -rf "${TEMPORARY_FIXTURES}"' EXIT
cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/bad-isr"
sed -i 's/Isr: 1,2,3$/Isr: 1/' "${TEMPORARY_FIXTURES}/bad-isr/matching.events.topic.txt"
assert_fails 'insufficient ISR' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/bad-isr" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/unsafe-broker"
sed -i 's/auto.create.topics.enable=false/auto.create.topics.enable=true/' \
  "${TEMPORARY_FIXTURES}/unsafe-broker/broker.config.txt"
assert_fails 'unsafe broker policy' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/unsafe-broker" --certify-production

provision_output="$("${PROVISIONER}" --bootstrap-server kafka:9092 --profile production \
  --certify-production --dry-run)"
[[ "${provision_output}" == *'--topic matching.commands --partitions 15 --replication-factor 3'* ]] || {
  printf '%s\n' 'Production provisioning command is incomplete' >&2
  exit 1
}
[[ "${provision_output}" == *'cleanup.policy=delete'* && \
  "${provision_output}" == *'min.insync.replicas=2'* ]] || {
  printf '%s\n' 'Production topic configuration is incomplete' >&2
  exit 1
}

printf '%s\n' 'Matching Kafka profile tests passed.'

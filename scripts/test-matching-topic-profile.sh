#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="${SCRIPT_DIR}/testdata/matching-topic-profile/valid"
VALIDATOR="${SCRIPT_DIR}/validate-matching-topic-profile.sh"
PROVISIONER="${SCRIPT_DIR}/provision-matching-topics.sh"
PRODUCER_CONFIG_FILE="${FIXTURE_DIR}/matching.producer.config.txt"
CAPACITY_EVIDENCE_FILE="${FIXTURE_DIR}/capacity.properties"
TEMPORARY_FIXTURES="$(mktemp -d)"
trap 'rm -rf "${TEMPORARY_FIXTURES}"' EXIT

assert_fails() {
  local description="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    printf '%s unexpectedly succeeded\n' "${description}" >&2
    exit 1
  fi
}

"${VALIDATOR}" --profile production --fixture-dir "${FIXTURE_DIR}" \
  --command-config "${SCRIPT_DIR}/../config/kafka/matching-production.properties" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" \
  --certify-production
assert_fails 'local profile production certification' "${VALIDATOR}" --profile local \
  --fixture-dir "${FIXTURE_DIR}" --certify-production
assert_fails 'missing producer evidence' "${VALIDATOR}" --profile production \
  --fixture-dir "${FIXTURE_DIR}" --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" \
  --certify-production
assert_fails 'missing capacity evidence' "${VALIDATOR}" --profile production \
  --fixture-dir "${FIXTURE_DIR}" --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/partition-count-drift"
sed -i 's/PartitionCount: 15/PartitionCount: 14/' \
  "${TEMPORARY_FIXTURES}/partition-count-drift/matching.events.topic.txt"
assert_fails 'partition count drift' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/partition-count-drift" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/replication-drift"
sed -i 's/ReplicationFactor: 3/ReplicationFactor: 2/' \
  "${TEMPORARY_FIXTURES}/replication-drift/matching.commands.topic.txt"
assert_fails 'replication factor drift' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/replication-drift" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/bad-isr"
sed -i 's/Isr: 1,2,3$/Isr: 1/' "${TEMPORARY_FIXTURES}/bad-isr/matching.events.topic.txt"
assert_fails 'insufficient ISR' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/bad-isr" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/single-broker-loss"
for topic_file in "${TEMPORARY_FIXTURES}/single-broker-loss"/*.topic.txt; do
  sed -i -e 's/Leader: 3/Leader: 1/g' -e 's/Isr: 1,2,3$/Isr: 1,2/' "${topic_file}"
done
"${VALIDATOR}" --profile production --fixture-dir "${TEMPORARY_FIXTURES}/single-broker-loss" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/two-broker-loss"
for topic_file in "${TEMPORARY_FIXTURES}/two-broker-loss"/*.topic.txt; do
  sed -i -e 's/Leader: [123]/Leader: 1/g' -e 's/Isr: 1,2,3$/Isr: 1/' "${topic_file}"
done
assert_fails 'two-broker loss' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/two-broker-loss" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/no-leader"
sed -i 's/Leader: [123]/Leader: -1/' \
  "${TEMPORARY_FIXTURES}/no-leader/matching.commands.topic.txt"
assert_fails 'leader loss without an eligible leader' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/no-leader" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/duplicate-replica"
sed -i 's/Replicas: 1,2,3/Replicas: 1,2,1/' \
  "${TEMPORARY_FIXTURES}/duplicate-replica/matching.commands.topic.txt"
assert_fails 'duplicate replica broker identity' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/duplicate-replica" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/isr-outside-replicas"
sed -i 's/Isr: 1,2,3$/Isr: 1,2,4/' \
  "${TEMPORARY_FIXTURES}/isr-outside-replicas/matching.commands.topic.txt"
assert_fails 'ISR broker outside replica set' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/isr-outside-replicas" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/replica-set-drift"
sed -i '/Partition: 1/s/Replicas: 1,2,3/Replicas: 1,2,4/; /Partition: 1/s/Isr: 1,2,3/Isr: 1,2,4/' \
  "${TEMPORARY_FIXTURES}/replica-set-drift/matching.commands.topic.txt"
assert_fails 'replica broker identity set drift' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/replica-set-drift" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/unsafe-broker"
sed -i 's/auto.create.topics.enable=false/auto.create.topics.enable=true/' \
  "${TEMPORARY_FIXTURES}/unsafe-broker/broker.config.txt"
assert_fails 'unsafe broker policy' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/unsafe-broker" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/unsafe-unclean-election"
sed -i 's/unclean.leader.election.enable=false/unclean.leader.election.enable=true/' \
  "${TEMPORARY_FIXTURES}/unsafe-unclean-election/broker.config.txt"
assert_fails 'unsafe unclean election policy' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/unsafe-unclean-election" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/compacted-topic"
sed -i 's/cleanup.policy=delete,retention/cleanup.policy=delete,compact,retention/' \
  "${TEMPORARY_FIXTURES}/compacted-topic/matching.commands.config.txt"
assert_fails 'compacted topic cleanup policy' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/compacted-topic" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp "${CAPACITY_EVIDENCE_FILE}" "${TEMPORARY_FIXTURES}/under-capacity.properties"
sed -i 's/capacity.usable.cluster.bytes=200000000000/capacity.usable.cluster.bytes=1000000000/' \
  "${TEMPORARY_FIXTURES}/under-capacity.properties"
assert_fails 'insufficient 30-day capacity' "${VALIDATOR}" --profile production \
  --fixture-dir "${FIXTURE_DIR}" --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${TEMPORARY_FIXTURES}/under-capacity.properties" --certify-production

cp "${PRODUCER_CONFIG_FILE}" "${TEMPORARY_FIXTURES}/unsafe-producer.config.txt"
sed -i 's/^acks=all$/acks=1/' "${TEMPORARY_FIXTURES}/unsafe-producer.config.txt"
assert_fails 'unsafe producer acknowledgement' "${VALIDATOR}" --profile production \
  --fixture-dir "${FIXTURE_DIR}" \
  --producer-config-file "${TEMPORARY_FIXTURES}/unsafe-producer.config.txt" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp -R "${FIXTURE_DIR}" "${TEMPORARY_FIXTURES}/retention-drift"
sed -i 's/retention.ms=2592000000/retention.ms=86400000/' \
  "${TEMPORARY_FIXTURES}/retention-drift/matching.events.config.txt"
assert_fails 'retention drift' "${VALIDATOR}" --profile production \
  --fixture-dir "${TEMPORARY_FIXTURES}/retention-drift" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

cp "${PRODUCER_CONFIG_FILE}" "${TEMPORARY_FIXTURES}/unsafe-idempotence.config.txt"
sed -i 's/^enable.idempotence=true$/enable.idempotence=false/' \
  "${TEMPORARY_FIXTURES}/unsafe-idempotence.config.txt"
assert_fails 'unsafe producer idempotence' "${VALIDATOR}" --profile production \
  --fixture-dir "${FIXTURE_DIR}" \
  --producer-config-file "${TEMPORARY_FIXTURES}/unsafe-idempotence.config.txt" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" --certify-production

provision_output="$("${PROVISIONER}" --bootstrap-server kafka:9092 --profile production \
  --command-config "${SCRIPT_DIR}/../config/kafka/matching-production.properties" \
  --producer-config-file "${PRODUCER_CONFIG_FILE}" \
  --capacity-evidence-file "${CAPACITY_EVIDENCE_FILE}" \
  --certify-production --dry-run)"
[[ "${provision_output}" == *'--topic matching.commands --partitions 15 --replication-factor 3'* ]] || {
  printf '%s\n' 'Production provisioning command is incomplete' >&2
  exit 1
}
[[ "${provision_output}" == *'--topic matching.events --partitions 15 --replication-factor 3'* ]] || {
  printf '%s\n' 'Matching events provisioning command is incomplete' >&2
  exit 1
}
[[ "${provision_output}" == *'cleanup.policy=delete'* && \
  "${provision_output}" == *'retention.ms=2592000000'* && \
  "${provision_output}" == *'min.insync.replicas=2'* ]] || {
  printf '%s\n' 'Production topic configuration is incomplete' >&2
  exit 1
}
[[ "${provision_output}" == *'--command-config'* ]] || {
  printf '%s\n' 'Kafka command config was not forwarded to the provisioning command' >&2
  exit 1
}

printf '%s\n' 'Matching Kafka profile tests passed.'

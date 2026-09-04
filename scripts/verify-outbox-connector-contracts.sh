#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

command -v jq >/dev/null 2>&1 || {
  echo "jq is required" >&2
  exit 1
}

assert_equal() {
  local actual="$1"
  local expected="$2"
  local description="$3"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "${description}: expected '${expected}', got '${actual}'" >&2
    exit 1
  fi
}

assert_contains() {
  local actual="$1"
  local expected="$2"
  local description="$3"
  if [[ ",${actual}," != *",${expected},"* ]]; then
    echo "${description}: '${expected}' is missing from '${actual}'" >&2
    exit 1
  fi
}

assert_absent() {
  local config="$1"
  local option="$2"
  local description="$3"

  if jq -e --arg option "$option" '.config | has($option)' "$config" >/dev/null; then
    echo "${description}: unsupported option '${option}' is present" >&2
    exit 1
  fi
}

verify_connector() {
  local service="$1"
  local config_name="$2"
  local owner_table="$3"
  local name="${config_name}-outbox"
  local config="${REPO_ROOT}/deploy/compose/${config_name}-outbox-connector.json"
  local placement
  assert_equal "$(jq -r '.name' "${config}")" "${name}" "${service} connector name"
  assert_equal "$(jq -r '.config["table.include.list"]' "${config}")" "${owner_table}" \
    "${service} connector table scope"
  assert_equal "$(jq -r '.config["key.converter"]' "${config}")" \
    "org.apache.kafka.connect.storage.StringConverter" "${service} key converter"
  assert_equal "$(jq -r '.config["key.converter.schemas.enable"]' "${config}")" "false" \
    "${service} key schema setting"
  assert_equal "$(jq -r '.config["value.converter"]' "${config}")" \
    "org.apache.kafka.connect.converters.ByteArrayConverter" "${service} value converter"
  assert_equal "$(jq -r '.config["transforms.outbox.type"]' "${config}")" \
    "io.debezium.transforms.outbox.EventRouter" "${service} event router"
  assert_equal "$(jq -r '.config["transforms.outbox.table.field.event.id"]' "${config}")" \
    "event_id" "${service} event id mapping"
  assert_equal "$(jq -r '.config["transforms.outbox.table.field.event.key"]' "${config}")" \
    "message_key" "${service} message key mapping"
  assert_equal "$(jq -r '.config["transforms.outbox.table.field.event.payload"]' "${config}")" \
    "payload" "${service} payload mapping"
  assert_equal "$(jq -r '.config["transforms.outbox.table.field.event.timestamp"]' "${config}")" \
    "created_at" "${service} logical timestamp mapping"
  assert_absent "${config}" "transforms.outbox.table.field.event.type" \
    "${service} payload type mapping"
  placement="$(jq -r '.config["transforms.outbox.table.fields.additional.placement"]' "${config}")"
  assert_contains "${placement}" "kafka_partition_id:partition" "${service} partition mapping"
  assert_contains "${placement}" "headers_json:header:headers_json" "${service} header mapping"
  assert_contains "${placement}" "payload_type:header:eventType" \
    "${service} event type header mapping"
  if jq -e --arg other "${4:-unused}" \
      '.config["table.include.list"] == $other' "${config}" >/dev/null; then
    echo "${service} connector crosses service ownership" >&2
    exit 1
  fi
}

assert_no_direct_kafka_publisher() {
  local service="$1" source_dir="$2" matches
  matches="$(grep -R -n -E --include='*.java' \
    'KafkaTemplate|ProducerFactory|ProducerRecord|org\.apache\.kafka\.clients\.producer' \
    "$source_dir" || true)"
  if [[ -n "$matches" ]]; then
    echo "${service} production code must publish through its transactional outbox, not Kafka directly:" >&2
    printf '%s\n' "$matches" >&2
    exit 1
  fi
}

verify_connector "risk" "risk-service" "risk_service.outbox" "account_service.outbox"
verify_connector "account" "account-service" "account_service.outbox" "risk_service.outbox"

assert_no_direct_kafka_publisher \
  "risk" "${REPO_ROOT}/services/risk-service/src/main/java"
assert_no_direct_kafka_publisher \
  "account" "${REPO_ROOT}/services/account-service/src/main/java"

echo "Risk and Account outbox connector contracts are valid."
echo "Risk and Account production code contain no direct Kafka producer path."

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/deploy/compose/kafka-connect.local.yml"
export SIMPLEMATCH_POSTGRES_PORT="${SIMPLEMATCH_POSTGRES_PORT:-15432}"
EVENT_BASE_MS="$(date +%s%3N)"
COMPOSE=(docker compose -f "${COMPOSE_FILE}")

command -v docker >/dev/null 2>&1 || {
  echo "docker is required" >&2
  exit 1
}
command -v jq >/dev/null 2>&1 || {
  echo "jq is required" >&2
  exit 1
}

cleanup() {
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for_postgres() {
  for _ in $(seq 1 60); do
    if "${COMPOSE[@]}" exec -T postgres pg_isready -U simplematch -d simplematch >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "PostgreSQL did not become ready" >&2
  exit 1
}

wait_for_connect() {
  for _ in $(seq 1 60); do
    if curl -fsS http://localhost:8083/connectors >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "Kafka Connect did not become ready" >&2
  exit 1
}

wait_for_connector() {
  local name="$1"
  for _ in $(seq 1 60); do
    if curl -fsS "http://localhost:8083/connectors/${name}/status" 2>/dev/null \
        | jq -e '.connector.state == "RUNNING" and .tasks[0].state == "RUNNING"' >/dev/null; then
      return
    fi
    sleep 1
  done
  curl -fsS "http://localhost:8083/connectors/${name}/status" >&2 || true
  echo "Connector ${name} did not become ready" >&2
  exit 1
}

wait_for_records() {
  local topic="$1"
  local expected_count="$2"
  local actual_count
  actual_count="$("${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:29092 \
    --topic "${topic}" \
    --from-beginning \
    --max-messages "${expected_count}" \
    --timeout-ms 30000 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "${actual_count}" -ge "${expected_count}" ]]; then
    return
  fi
  echo "Topic ${topic} did not reach ${expected_count} records" >&2
  exit 1
}

assert_contains() {
  local actual="$1"
  local expected="$2"
  local description="$3"
  if [[ "${actual}" != *"${expected}"* ]]; then
    echo "${description}: expected '${expected}' in '${actual}'" >&2
    exit 1
  fi
}

assert_payload() {
  local topic="$1"
  local partition="$2"
  local expected_payload="$3"
  local actual_hex expected_hex
  actual_hex="$("${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:29092 \
    --topic "${topic}" \
    --partition "${partition}" \
    --offset 0 \
    --max-messages 1 \
    --timeout-ms 10000 2>/dev/null | od -An -tx1 | tr -d ' \n')"
  expected_hex="$(printf '%s\n' "${expected_payload}" | od -An -tx1 | tr -d ' \n')"
  if [[ "${actual_hex}" != "${expected_hex}" ]]; then
    echo "${topic} payload bytes: expected ${expected_hex}, got ${actual_hex}" >&2
    exit 1
  fi
}

assert_record_metadata() {
  local topic="$1"
  local partition="$2"
  local expected_key="$3"
  local expected_timestamp="$4"
  local expected_header="$5"
  local metadata
  metadata="$("${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:29092 \
    --topic "${topic}" \
    --partition "${partition}" \
    --offset 0 \
    --max-messages 1 \
    --timeout-ms 10000 \
    --property print.key=true \
    --property print.partition=true \
    --property print.timestamp=true \
    --property print.headers=true \
    --property print.value=false 2>/dev/null)"
  assert_contains "${metadata}" "${expected_key}" "${topic} message key"
  assert_contains "${metadata}" "Partition:${partition}" "${topic} partition"
  assert_contains "${metadata}" "${expected_timestamp}" "${topic} timestamp"
  assert_contains "${metadata}" "${expected_header}" "${topic} header"
}

echo "Starting PostgreSQL, Kafka, and Kafka Connect..."
"${COMPOSE[@]}" up -d >/dev/null
wait_for_postgres
wait_for_connect

"${COMPOSE[@]}" exec -T postgres psql -U simplematch -d simplematch <<'SQL'
CREATE SCHEMA risk_service;
CREATE SCHEMA account_service;
CREATE SCHEMA marketdata_publisher;

CREATE TABLE risk_service.outbox (
  id BIGINT PRIMARY KEY,
  event_id UUID NOT NULL UNIQUE,
  topic VARCHAR(255) NOT NULL,
  message_key VARCHAR(255) NOT NULL,
  kafka_partition_id INTEGER,
  payload BYTEA NOT NULL,
  payload_type VARCHAR(255) NOT NULL,
  headers_json TEXT NOT NULL,
  aggregate_type VARCHAR(255) NOT NULL,
  aggregate_id VARCHAR(255) NOT NULL,
  created_at_unix_ms BIGINT NOT NULL,
  created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);
CREATE TABLE account_service.outbox (LIKE risk_service.outbox INCLUDING ALL);
CREATE TABLE marketdata_publisher.outbox (LIKE risk_service.outbox INCLUDING ALL);

SQL

for topic in orders.validated account.lifecycle market-reference.routing-policies; do
  "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:29092 --create --if-not-exists \
    --topic "${topic}" --partitions 3 --replication-factor 1 >/dev/null
done

POSTGRES_HOST=postgres POSTGRES_PORT=5432 POSTGRES_USER=simplematch \
  POSTGRES_PASSWORD=simplematch POSTGRES_DB=simplematch \
  "${SCRIPT_DIR}/../deploy/compose/apply-risk-service-outbox-connector.sh"
wait_for_connector risk-service-outbox

"${COMPOSE[@]}" exec -T postgres psql -U simplematch -d simplematch <<SQL
INSERT INTO risk_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (1, '00000000-0000-0000-0000-000000000001', 'orders.validated', 'risk-order-1', 2,
   decode('7269736b2d7061796c6f61642d7631', 'hex'), 'risk.v1',
   '{"trace-id":"risk-1"}', 'Admission', 'risk-order-1', ${EVENT_BASE_MS},
   to_timestamp(${EVENT_BASE_MS} / 1000.0) AT TIME ZONE 'UTC');
SQL
wait_for_records orders.validated 1

POSTGRES_HOST=postgres POSTGRES_PORT=5432 POSTGRES_USER=simplematch \
  POSTGRES_PASSWORD=simplematch POSTGRES_DB=simplematch \
  "${SCRIPT_DIR}/../deploy/compose/apply-account-service-outbox-connector.sh"
wait_for_connector account-service-outbox

"${COMPOSE[@]}" exec -T postgres psql -U simplematch -d simplematch <<SQL
INSERT INTO account_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (1, '00000000-0000-0000-0000-000000000002', 'account.lifecycle', 'account-1', NULL,
   decode('6163636f756e742d7061796c6f61642d7631', 'hex'), 'account.v1',
   '{"trace-id":"account-1"}', 'AccountReservation', 'account-order-1', $((EVENT_BASE_MS + 1000)),
   to_timestamp($((EVENT_BASE_MS + 1000)) / 1000.0) AT TIME ZONE 'UTC');
SQL
wait_for_records account.lifecycle 1

POSTGRES_HOST=postgres POSTGRES_PORT=5432 POSTGRES_USER=simplematch \
  POSTGRES_PASSWORD=simplematch POSTGRES_DB=simplematch \
  "${SCRIPT_DIR}/../deploy/compose/apply-marketdata-publisher-outbox-connector.sh"
wait_for_connector marketdata-publisher-outbox

"${COMPOSE[@]}" exec -T postgres psql -U simplematch -d simplematch <<SQL
INSERT INTO marketdata_publisher.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (1, '00000000-0000-0000-0000-000000000003', 'market-reference.routing-policies', '2330', 1,
   decode('6d61726b65742d7061796c6f61642d7631', 'hex'), 'routing-policy.v1',
   '{"trace-id":"market-1"}', 'RoutingPolicy', 'routing-policy-1', $((EVENT_BASE_MS + 2000)),
   to_timestamp($((EVENT_BASE_MS + 2000)) / 1000.0) AT TIME ZONE 'UTC');
SQL
wait_for_records market-reference.routing-policies 1

assert_payload orders.validated 2 risk-payload-v1
assert_payload market-reference.routing-policies 1 market-payload-v1
assert_record_metadata orders.validated 2 'risk-order-1' "${EVENT_BASE_MS}" 'headers_json:{"trace-id":"risk-1"}'
assert_record_metadata market-reference.routing-policies 1 '2330' "$((EVENT_BASE_MS + 2000))" 'headers_json:{"trace-id":"market-1"}'

echo "Verified exact payload bytes, key, explicit partition, timestamp, and headers."

curl -fsS -X PUT http://localhost:8083/connectors/risk-service-outbox/pause >/dev/null
"${COMPOSE[@]}" exec -T postgres psql -U simplematch -d simplematch <<SQL
INSERT INTO risk_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (2, '00000000-0000-0000-0000-000000000004', 'orders.validated', 'risk-order-2', 2,
   decode('7269736b2d7061796c6f61642d7632', 'hex'), 'risk.v1',
   '{"trace-id":"risk-2"}', 'Admission', 'risk-order-2', $((EVENT_BASE_MS + 3000)),
   to_timestamp($((EVENT_BASE_MS + 3000)) / 1000.0) AT TIME ZONE 'UTC');
SELECT CASE WHEN COUNT(*) = 2 THEN 'outbox row retained while connector is paused' ELSE 'unexpected outbox count' END
FROM risk_service.outbox;
SQL
curl -fsS -X PUT http://localhost:8083/connectors/risk-service-outbox/resume >/dev/null
wait_for_connector risk-service-outbox
wait_for_records orders.validated 2

echo "Verified connector outage recovery and durable outbox retention."
echo "Outbox CDC contract check passed."

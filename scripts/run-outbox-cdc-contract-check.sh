#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/deploy/compose/kafka-connect.local.yml"
export SIMPLEMATCH_POSTGRES_PORT="${SIMPLEMATCH_POSTGRES_PORT:-15432}"
export SIMPLEMATCH_CONNECT_OFFSET_FLUSH_INTERVAL_MS="${SIMPLEMATCH_CONNECT_OFFSET_FLUSH_INTERVAL_MS:-120000}"
EVENT_BASE_MS="$(date +%s%3N)"
RUN_EPOCH="$(date +%s)"
COMPOSE_PROJECT_NAME="${SIMPLEMATCH_CDC_COMPOSE_PROJECT:-simplematch-cdc-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}-${RUN_EPOCH}-$$}"
if [[ ! "$COMPOSE_PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
  echo "Invalid CDC Compose project name: ${COMPOSE_PROJECT_NAME}" >&2
  exit 1
fi
export COMPOSE_PROJECT_NAME
COMPOSE=(docker compose --project-name "${COMPOSE_PROJECT_NAME}" -f "${COMPOSE_FILE}")
# shellcheck source=scripts/lib/cdc-verifier.sh
source "${SCRIPT_DIR}/lib/cdc-verifier.sh"

for command in docker jq curl awk od sort xxd sha256sum; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "$command is required" >&2
    exit 1
  }
done

docker compose version >/dev/null 2>&1 || {
  echo "docker compose is required" >&2
  exit 1
}

project_label="com.docker.compose.project=${COMPOSE_PROJECT_NAME}"
existing_containers=''
existing_networks=''
existing_volumes=''

refresh_project_resources() {
  existing_containers="$(docker ps -aq --filter "label=${project_label}")" || return 1
  existing_networks="$(docker network ls -q --filter "label=${project_label}")" || return 1
  existing_volumes="$(docker volume ls -q --filter "label=${project_label}")" || return 1
}

project_has_resources() {
  [[ -n "$existing_containers" || -n "$existing_networks" || -n "$existing_volumes" ]]
}

print_project_resources() {
  [[ -z "$existing_containers" ]] || printf '  containers: %s\n' "$(tr '\n' ' ' <<<"$existing_containers" | sed 's/[[:space:]]*$//')" >&2
  [[ -z "$existing_networks" ]] || printf '  networks: %s\n' "$(tr '\n' ' ' <<<"$existing_networks" | sed 's/[[:space:]]*$//')" >&2
  [[ -z "$existing_volumes" ]] || printf '  volumes: %s\n' "$(tr '\n' ' ' <<<"$existing_volumes" | sed 's/[[:space:]]*$//')" >&2
}

refresh_project_resources || {
  echo "Failed to inventory Docker resources for CDC Compose project ${COMPOSE_PROJECT_NAME}" >&2
  exit 1
}
if project_has_resources; then
  echo "CDC Compose project ${COMPOSE_PROJECT_NAME} already owns resources; choose a fresh project name" >&2
  print_project_resources
  exit 1
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
  local exit_status=$?
  if ! "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1; then
    echo "Failed to remove run-owned CDC Compose resources for ${COMPOSE_PROJECT_NAME}" >&2
    exit_status=1
  fi
  if ! refresh_project_resources; then
    echo "Failed to verify CDC Compose cleanup for ${COMPOSE_PROJECT_NAME}" >&2
    exit_status=1
  elif project_has_resources; then
    echo "CDC Compose cleanup left run-owned resources for ${COMPOSE_PROJECT_NAME}:" >&2
    print_project_resources
    exit_status=1
  fi
  rm -rf "$TMP_DIR"
  trap - EXIT
  exit "$exit_status"
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

wait_for_kafka() {
  for _ in $(seq 1 60); do
    if "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server kafka:29092 --list >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "Kafka did not become ready" >&2
  exit 1
}

print_connect_diagnostics() {
  local container_id="${1:-}"
  echo "Kafka Connect diagnostics:" >&2
  "${COMPOSE[@]}" ps -a >&2 || true
  if [[ -n "$container_id" ]]; then
    docker inspect --format \
      'id={{.Id}} status={{.State.Status}} running={{.State.Running}} restartCount={{.RestartCount}} exitCode={{.State.ExitCode}} startedAt={{.State.StartedAt}} restartPolicy={{.HostConfig.RestartPolicy.Name}}' \
      "$container_id" >&2 || true
  fi
  "${COMPOSE[@]}" logs --no-color --tail=200 kafka-connect >&2 || true
}

wait_for_connect() {
  for _ in $(seq 1 60); do
    if curl -fsS http://localhost:8083/connectors >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  local container_id
  container_id="$("${COMPOSE[@]}" ps -a -q kafka-connect 2>/dev/null || true)"
  echo "Kafka Connect did not become ready" >&2
  print_connect_diagnostics "$container_id"
  exit 1
}

wait_for_connect_crash_transition() {
  local container_id="$1"
  local initial_started_at="$2"
  local initial_restart_count="$3"
  local state running status started_at exit_code restart_count

  for _ in $(seq 1 120); do
    state="$(
      docker inspect --format \
        '{{.State.Running}}|{{.State.Status}}|{{.State.StartedAt}}|{{.State.ExitCode}}|{{.RestartCount}}' \
        "$container_id" 2>/dev/null || true
    )"
    IFS='|' read -r running status started_at exit_code restart_count <<<"$state"

    if [[ "$running" == false && "$status" == exited ]]; then
      if [[ "$exit_code" != 137 ]]; then
        echo "Kafka Connect exited with $exit_code after SIGKILL; expected 137" >&2
        print_connect_diagnostics "$container_id"
        return 1
      fi
      printf 'stopped\n'
      return 0
    fi

    if [[ "$running" == true && "$status" == running \
          && -n "$started_at" && "$started_at" != "$initial_started_at" \
          && "$restart_count" =~ ^[0-9]+$ \
          && "$initial_restart_count" =~ ^[0-9]+$ \
          && "$restart_count" -gt "$initial_restart_count" ]]; then
      printf 'restarted\n'
      return 0
    fi
    sleep 0.5
  done

  echo "Kafka Connect did not expose a completed SIGKILL transition" >&2
  print_connect_diagnostics "$container_id"
  return 1
}

wait_for_connect_new_incarnation() {
  local container_id="$1"
  local initial_started_at="$2"
  local state running status started_at

  for _ in $(seq 1 120); do
    state="$(
      docker inspect --format \
        '{{.State.Running}}|{{.State.Status}}|{{.State.StartedAt}}' \
        "$container_id" 2>/dev/null || true
    )"
    IFS='|' read -r running status started_at <<<"$state"
    if [[ "$running" == true && "$status" == running \
          && -n "$started_at" && "$started_at" != "$initial_started_at" ]]; then
      return 0
    fi
    sleep 0.5
  done

  echo "Kafka Connect did not start a new process incarnation after SIGKILL" >&2
  print_connect_diagnostics "$container_id"
  return 1
}

crash_connect_process() {
  local container_id initial_started_at initial_restart_count transition
  container_id="$("${COMPOSE[@]}" ps -q kafka-connect)"
  [[ -n "$container_id" ]] || {
    echo "Kafka Connect container is not running before crash injection" >&2
    exit 1
  }

  initial_started_at="$(docker inspect --format '{{.State.StartedAt}}' "$container_id")"
  initial_restart_count="$(docker inspect --format '{{.RestartCount}}' "$container_id")"
  [[ -n "$initial_started_at" ]] || {
    echo "Kafka Connect StartedAt is missing before crash injection" >&2
    print_connect_diagnostics "$container_id"
    exit 1
  }
  [[ "$initial_restart_count" =~ ^[0-9]+$ ]] || {
    echo "Kafka Connect restart count is invalid before crash injection" >&2
    print_connect_diagnostics "$container_id"
    exit 1
  }

  "${COMPOSE[@]}" kill -s SIGKILL kafka-connect >/dev/null
  transition="$(
    wait_for_connect_crash_transition \
      "$container_id" "$initial_started_at" "$initial_restart_count"
  )" || exit 1

  if [[ "$transition" == stopped ]]; then
    "${COMPOSE[@]}" start kafka-connect >/dev/null
  elif [[ "$transition" != restarted ]]; then
    echo "Kafka Connect crash transition is invalid: $transition" >&2
    print_connect_diagnostics "$container_id"
    exit 1
  fi

  wait_for_connect_new_incarnation "$container_id" "$initial_started_at" || exit 1
}

psql_query() {
  "${COMPOSE[@]}" exec -T postgres psql -X -v ON_ERROR_STOP=1 -U simplematch -d simplematch "$@"
}

cdc_outbox_exec() {
  local sql="$1"
  psql_query -At -F $'\t' -c "$sql"
}

cdc_connect_status_exec() {
  local connector_name="$1"
  curl -fsS "http://localhost:8083/connectors/${connector_name}/status"
}

CDC_KAFKA_EXEC=("${COMPOSE[@]}" exec -T kafka)
CDC_OUTBOX_EXEC=(cdc_outbox_exec)
CDC_CONNECT_STATUS_EXEC=(cdc_connect_status_exec)

wait_for_connector() {
  cdc_wait_for_connector_state "$1" RUNNING 60
}

connector_offsets() {
  local name="$1"
  curl -fsS "http://localhost:8083/connectors/${name}/offsets" | jq -cS '.offsets // []'
}

wait_for_connector_offset_change() {
  local name="$1" baseline="$2" current
  for _ in $(seq 1 180); do
    current="$(connector_offsets "$name" 2>/dev/null || true)"
    if [[ -n "$current" && "$current" != '[]' && "$current" != "$baseline" ]]; then
      return
    fi
    sleep 1
  done
  echo "Connector ${name} did not commit a new source offset" >&2
  exit 1
}

pause_connector() {
  local name="$1"
  curl -fsS -X PUT "http://localhost:8083/connectors/${name}/pause" >/dev/null
  cdc_wait_for_connector_state "$name" PAUSED 60
}

resume_connector() {
  local name="$1"
  curl -fsS -X PUT "http://localhost:8083/connectors/${name}/resume" >/dev/null
  cdc_wait_for_connector_state "$name" RUNNING 60
}

wait_for_all_connectors() {
  wait_for_connector risk-service-outbox
  wait_for_connector account-service-outbox
  wait_for_connector marketdata-publisher-outbox
}

capture_probe() {
  local schema="$1" aggregate_type="$2" aggregate_id="$3" output="$4"
  cdc_read_outbox_probe "$schema" "$aggregate_type" "$aggregate_id" "$output"
}

verify_probe_publication() {
  local probe="$1" baseline="$2" schema="$3" event_id
  event_id="$(jq -r '.event_id' "$probe")"
  cdc_assert_probe_publication "$probe" "$baseline" >/dev/null
  printf 'Verified %s event %s exact Kafka record.\n' "$schema" "$event_id"
}

assert_probe_unchanged() {
  local schema="$1" aggregate_type="$2" aggregate_id="$3" expected_probe="$4" observed_probe="$5"
  capture_probe "$schema" "$aggregate_type" "$aggregate_id" "$observed_probe"
  cdc_assert_same_probe "$expected_probe" "$observed_probe"
}

echo "Starting PostgreSQL, Kafka, and Kafka Connect in Compose project ${COMPOSE_PROJECT_NAME}..."
"${COMPOSE[@]}" up -d >/dev/null
wait_for_postgres
wait_for_kafka
wait_for_connect

psql_query <<'SQL'
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
    --topic "$topic" --partitions 3 --replication-factor 1 >/dev/null
done

POSTGRES_HOST=postgres POSTGRES_PORT=5432 POSTGRES_USER=simplematch \
  POSTGRES_PASSWORD=simplematch POSTGRES_DB=simplematch \
  "${SCRIPT_DIR}/../deploy/compose/apply-risk-service-outbox-connector.sh"
wait_for_connector risk-service-outbox

risk_baseline="$TMP_DIR/risk-baseline.tsv"
risk_probe="$TMP_DIR/risk-probe.json"
cdc_capture_topic_end_offsets orders.validated "$risk_baseline"
psql_query <<SQL
INSERT INTO risk_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (1, '00000000-0000-0000-0000-000000000001', 'orders.validated', 'risk-order-1', 2,
   decode('7269736b2d7061796c6f61642d7631', 'hex'), 'risk.v1',
   '{"trace-id":"risk-1"}', 'Admission', 'risk-order-1', ${EVENT_BASE_MS},
   to_timestamp(${EVENT_BASE_MS} / 1000.0) AT TIME ZONE 'UTC');
SQL
capture_probe risk_service Admission risk-order-1 "$risk_probe"
verify_probe_publication "$risk_probe" "$risk_baseline" risk_service

POSTGRES_HOST=postgres POSTGRES_PORT=5432 POSTGRES_USER=simplematch \
  POSTGRES_PASSWORD=simplematch POSTGRES_DB=simplematch \
  "${SCRIPT_DIR}/../deploy/compose/apply-account-service-outbox-connector.sh"
wait_for_connector account-service-outbox

account_baseline="$TMP_DIR/account-baseline.tsv"
account_probe="$TMP_DIR/account-probe.json"
cdc_capture_topic_end_offsets account.lifecycle "$account_baseline"
psql_query <<SQL
INSERT INTO account_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (1, '00000000-0000-0000-0000-000000000002', 'account.lifecycle', 'account-1', NULL,
   decode('6163636f756e742d7061796c6f61642d7631', 'hex'), 'account.v1',
   '{"trace-id":"account-1"}', 'account_reservation', 'account-reservation-1', $((EVENT_BASE_MS + 1000)),
   to_timestamp($((EVENT_BASE_MS + 1000)) / 1000.0) AT TIME ZONE 'UTC');
SQL
capture_probe account_service account_reservation account-reservation-1 "$account_probe"
verify_probe_publication "$account_probe" "$account_baseline" account_service

POSTGRES_HOST=postgres POSTGRES_PORT=5432 POSTGRES_USER=simplematch \
  POSTGRES_PASSWORD=simplematch POSTGRES_DB=simplematch \
  "${SCRIPT_DIR}/../deploy/compose/apply-marketdata-publisher-outbox-connector.sh"
wait_for_connector marketdata-publisher-outbox

market_baseline="$TMP_DIR/market-baseline.tsv"
market_probe="$TMP_DIR/market-probe.json"
cdc_capture_topic_end_offsets market-reference.routing-policies "$market_baseline"
psql_query <<SQL
INSERT INTO marketdata_publisher.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (1, '00000000-0000-0000-0000-000000000003', 'market-reference.routing-policies', '2330', 1,
   decode('6d61726b65742d7061796c6f61642d7631', 'hex'), 'routing-policy.v1',
   '{"trace-id":"market-1"}', 'RoutingPolicy', 'routing-policy-1', $((EVENT_BASE_MS + 2000)),
   to_timestamp($((EVENT_BASE_MS + 2000)) / 1000.0) AT TIME ZONE 'UTC');
SQL
capture_probe marketdata_publisher RoutingPolicy routing-policy-1 "$market_probe"
verify_probe_publication "$market_probe" "$market_baseline" marketdata_publisher

echo "Verified baseline exact payload bytes, event identity, key, partition semantics, timestamp, topic, and headers."

pause_connector risk-service-outbox
risk_recovery_baseline="$TMP_DIR/risk-recovery-baseline.tsv"
risk_recovery_probe="$TMP_DIR/risk-recovery-probe.json"
risk_recovery_after="$TMP_DIR/risk-recovery-after.json"
cdc_capture_topic_end_offsets orders.validated "$risk_recovery_baseline"
psql_query <<SQL
INSERT INTO risk_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (2, '00000000-0000-0000-0000-000000000004', 'orders.validated', 'risk-order-2', 2,
   decode('7269736b2d7061796c6f61642d7632', 'hex'), 'risk.v1',
   '{"trace-id":"risk-2"}', 'Admission', 'risk-order-2', $((EVENT_BASE_MS + 3000)),
   to_timestamp($((EVENT_BASE_MS + 3000)) / 1000.0) AT TIME ZONE 'UTC');
SQL
capture_probe risk_service Admission risk-order-2 "$risk_recovery_probe"
resume_connector risk-service-outbox
verify_probe_publication "$risk_recovery_probe" "$risk_recovery_baseline" risk_service
assert_probe_unchanged risk_service Admission risk-order-2 \
  "$risk_recovery_probe" "$risk_recovery_after"

pause_connector account-service-outbox
account_recovery_baseline="$TMP_DIR/account-recovery-baseline.tsv"
account_recovery_probe="$TMP_DIR/account-recovery-probe.json"
account_recovery_after="$TMP_DIR/account-recovery-after.json"
cdc_capture_topic_end_offsets account.lifecycle "$account_recovery_baseline"
psql_query <<SQL
INSERT INTO account_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (2, '00000000-0000-0000-0000-000000000005', 'account.lifecycle', 'account-2', NULL,
   decode('6163636f756e742d7061796c6f61642d7632', 'hex'), 'account.v1',
   '{"trace-id":"account-2"}', 'account_reservation', 'account-reservation-2', $((EVENT_BASE_MS + 4000)),
   to_timestamp($((EVENT_BASE_MS + 4000)) / 1000.0) AT TIME ZONE 'UTC');
SQL
capture_probe account_service account_reservation account-reservation-2 "$account_recovery_probe"
resume_connector account-service-outbox
verify_probe_publication "$account_recovery_probe" "$account_recovery_baseline" account_service
assert_probe_unchanged account_service account_reservation account-reservation-2 \
  "$account_recovery_probe" "$account_recovery_after"

echo "Verified Risk and Account connector outage recovery."

risk_offsets_before_broker_failure="$(connector_offsets risk-service-outbox)"
account_offsets_before_broker_failure="$(connector_offsets account-service-outbox)"
risk_broker_baseline="$TMP_DIR/risk-broker-baseline.tsv"
account_broker_baseline="$TMP_DIR/account-broker-baseline.tsv"
risk_broker_probe="$TMP_DIR/risk-broker-probe.json"
account_broker_probe="$TMP_DIR/account-broker-probe.json"
risk_broker_after="$TMP_DIR/risk-broker-after.json"
account_broker_after="$TMP_DIR/account-broker-after.json"
cdc_capture_topic_end_offsets orders.validated "$risk_broker_baseline"
cdc_capture_topic_end_offsets account.lifecycle "$account_broker_baseline"
"${COMPOSE[@]}" stop kafka >/dev/null

psql_query <<SQL
INSERT INTO risk_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (3, '00000000-0000-0000-0000-000000000006', 'orders.validated', 'risk-order-3', 1,
   decode('7269736b2d70726f64756365722d6661696c757265', 'hex'), 'risk.v1',
   '{"trace-id":"risk-producer-failure"}', 'Admission', 'risk-order-3', $((EVENT_BASE_MS + 5000)),
   to_timestamp($((EVENT_BASE_MS + 5000)) / 1000.0) AT TIME ZONE 'UTC');
INSERT INTO account_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (3, '00000000-0000-0000-0000-000000000007', 'account.lifecycle', 'account-3', NULL,
   decode('6163636f756e742d70726f64756365722d6661696c757265', 'hex'), 'account.v1',
   '{"trace-id":"account-producer-failure"}', 'account_reservation', 'account-reservation-3', $((EVENT_BASE_MS + 6000)),
   to_timestamp($((EVENT_BASE_MS + 6000)) / 1000.0) AT TIME ZONE 'UTC');
SQL
capture_probe risk_service Admission risk-order-3 "$risk_broker_probe"
capture_probe account_service account_reservation account-reservation-3 "$account_broker_probe"

"${COMPOSE[@]}" start kafka >/dev/null
wait_for_kafka
"${COMPOSE[@]}" up -d kafka-connect >/dev/null
wait_for_connect
wait_for_all_connectors
verify_probe_publication "$risk_broker_probe" "$risk_broker_baseline" risk_service
verify_probe_publication "$account_broker_probe" "$account_broker_baseline" account_service
assert_probe_unchanged risk_service Admission risk-order-3 \
  "$risk_broker_probe" "$risk_broker_after"
assert_probe_unchanged account_service account_reservation account-reservation-3 \
  "$account_broker_probe" "$account_broker_after"

echo "Verified Risk and Account recovery from Kafka producer unavailability."

wait_for_connector_offset_change risk-service-outbox "$risk_offsets_before_broker_failure"
wait_for_connector_offset_change account-service-outbox "$account_offsets_before_broker_failure"

risk_duplicate_event_id='00000000-0000-0000-0000-000000000008'
account_duplicate_event_id='00000000-0000-0000-0000-000000000009'
risk_duplicate_first_baseline="$TMP_DIR/risk-duplicate-first-baseline.tsv"
account_duplicate_first_baseline="$TMP_DIR/account-duplicate-first-baseline.tsv"
risk_duplicate_probe="$TMP_DIR/risk-duplicate-probe.json"
account_duplicate_probe="$TMP_DIR/account-duplicate-probe.json"
cdc_capture_topic_end_offsets orders.validated "$risk_duplicate_first_baseline"
cdc_capture_topic_end_offsets account.lifecycle "$account_duplicate_first_baseline"
psql_query <<SQL
INSERT INTO risk_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (4, '${risk_duplicate_event_id}', 'orders.validated', 'risk-duplicate', 0,
   decode('7269736b2d6475706c69636174652d7631', 'hex'), 'risk.v1',
   '{"trace-id":"risk-duplicate"}', 'Admission', 'risk-order-duplicate', $((EVENT_BASE_MS + 7000)),
   to_timestamp($((EVENT_BASE_MS + 7000)) / 1000.0) AT TIME ZONE 'UTC');
INSERT INTO account_service.outbox (
  id, event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES
  (4, '${account_duplicate_event_id}', 'account.lifecycle', 'account-duplicate', NULL,
   decode('6163636f756e742d6475706c69636174652d7631', 'hex'), 'account.v1',
   '{"trace-id":"account-duplicate"}', 'account_reservation', 'account-reservation-duplicate', $((EVENT_BASE_MS + 8000)),
   to_timestamp($((EVENT_BASE_MS + 8000)) / 1000.0) AT TIME ZONE 'UTC');
SQL
capture_probe risk_service Admission risk-order-duplicate "$risk_duplicate_probe"
capture_probe account_service account_reservation account-reservation-duplicate "$account_duplicate_probe"
verify_probe_publication "$risk_duplicate_probe" "$risk_duplicate_first_baseline" risk_service
verify_probe_publication "$account_duplicate_probe" "$account_duplicate_first_baseline" account_service

risk_duplicate_redelivery_baseline="$TMP_DIR/risk-duplicate-redelivery-baseline.tsv"
account_duplicate_redelivery_baseline="$TMP_DIR/account-duplicate-redelivery-baseline.tsv"
risk_duplicate_after="$TMP_DIR/risk-duplicate-after.json"
account_duplicate_after="$TMP_DIR/account-duplicate-after.json"
cdc_capture_topic_end_offsets orders.validated "$risk_duplicate_redelivery_baseline"
cdc_capture_topic_end_offsets account.lifecycle "$account_duplicate_redelivery_baseline"
crash_connect_process
wait_for_connect
wait_for_all_connectors
verify_probe_publication "$risk_duplicate_probe" "$risk_duplicate_redelivery_baseline" risk_service
verify_probe_publication "$account_duplicate_probe" "$account_duplicate_redelivery_baseline" account_service
assert_probe_unchanged risk_service Admission risk-order-duplicate \
  "$risk_duplicate_probe" "$risk_duplicate_after"
assert_probe_unchanged account_service account_reservation account-reservation-duplicate \
  "$account_duplicate_probe" "$account_duplicate_after"

echo "Verified Risk and Account publication-level duplicate delivery after an abrupt Connect crash."
echo "Outbox CDC contract check passed."

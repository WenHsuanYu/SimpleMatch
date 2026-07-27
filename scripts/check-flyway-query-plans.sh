#!/usr/bin/env bash

set -euo pipefail

database_name="${1:?database name is required}"
service_name="${2:?service name is required}"
postgres_host="${CI_POSTGRES_HOST:-127.0.0.1}"
postgres_port="${CI_POSTGRES_PORT:-5432}"
postgres_user="${CI_POSTGRES_USER:-simplematch}"
postgres_password="${CI_POSTGRES_PASSWORD:-simplematch}"

psql_explain() {
  local sql_statement="$1"

  PGPASSWORD="$postgres_password" psql \
    --host "$postgres_host" \
    --port "$postgres_port" \
    --username "$postgres_user" \
    --dbname "$database_name" \
    --no-align \
    --tuples-only \
    --quiet \
    --set ON_ERROR_STOP=1 \
    --command "SET enable_seqscan = off; EXPLAIN (COSTS OFF) $sql_statement"
}

assert_index_plan() {
  local description="$1"
  local expected_index="$2"
  local sql_statement="$3"
  local plan

  plan="$(psql_explain "$sql_statement")"
  if [[ "$plan" != *"$expected_index"* ]]; then
    echo "Expected $description to use index $expected_index." >&2
    echo "$plan" >&2
    return 1
  fi
}

case "$service_name" in
  account-service)
    assert_index_plan \
      "account reservation idempotency lookup" \
      "uq_account_reservations_request_id" \
      "SELECT reservation_id FROM account_service.account_reservations WHERE request_id = 'plan-check'"
    ;;
  risk-service)
    assert_index_plan \
      "risk submission business-key lookup" \
      "risk_submissions_business_key_key" \
      "SELECT request_id FROM risk_service.risk_submissions WHERE sender_comp_id = 'sender' AND target_comp_id = 'target' AND trading_day = DATE '2026-07-27' AND command_type = 'COMMAND_TYPE_NEW' AND cl_ord_id = 'clord' AND business_key_surrogated = FALSE"
    assert_index_plan \
      "risk outbox chronological CDC scan" \
      "idx_outbox_created_at" \
      "SELECT event_id FROM risk_service.outbox ORDER BY created_at_unix_ms, id LIMIT 1"
    ;;
  persistence)
    assert_index_plan \
      "persistence inbox deduplication lookup" \
      "inbox_pkey" \
      "SELECT received_at_unix_ms FROM persistence.inbox WHERE consumer_name = 'persistence' AND event_id = '00000000-0000-0000-0000-000000000000'"
    ;;
  *)
    echo "Unknown Flyway service: $service_name" >&2
    exit 1
    ;;
esac

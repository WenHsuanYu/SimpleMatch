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
  market-data-projection)
    assert_index_plan \
      "market-data top-five bid lookup" \
      "order_book_entries_top_five_idx" \
      "SELECT price_units FROM market_data_projection.order_book_entries WHERE venue_mic = 'XTAI' AND symbol = '2330' AND side = 'B' ORDER BY price_units DESC LIMIT 5"
    assert_index_plan \
      "market-data pending outbox scan" \
      "market_data_events_outbox_pending_idx" \
      "SELECT event_id FROM market_data_projection.market_data_events_outbox WHERE published_at_unix_ms IS NULL ORDER BY created_at_unix_ms LIMIT 1"
    ;;
  query-service)
    assert_index_plan \
      "query order history by account" \
      "order_read_model_account_idx" \
      "SELECT order_id FROM query_service.order_read_model WHERE account_id = 'account' ORDER BY updated_at_unix_ms"
    assert_index_plan \
      "query executions by order chronology" \
      "execution_read_model_order_idx" \
      "SELECT execution_id FROM query_service.execution_read_model WHERE order_id = 'order' ORDER BY executed_at_unix_ms, execution_id"
    assert_index_plan \
      "query active market reference artifact" \
      "active_market_reference_artifact_idx" \
      "SELECT symbol FROM query_service.active_market_reference WHERE trading_day = DATE '2026-07-28' AND artifact_id = 'artifact'"
    ;;
  risk-service)
    assert_index_plan \
      "risk admission business-key lookup" \
      "uq_admission_business_key" \
      "SELECT command_id FROM risk_service.admission_journal WHERE sender_comp_id = 'sender' AND target_comp_id = 'target' AND trading_day = DATE '2026-07-27' AND cl_ord_id = 'clord'"
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
  quickfix-gateway)
    assert_index_plan \
      "Gateway pending FIX delivery scan" \
      "idx_qfg_delivery_pending" \
      "SELECT delivery_id FROM quickfix_gateway.fix_delivery_intents WHERE status = 'PENDING' ORDER BY source_partition, source_offset, delivery_index LIMIT 1"
    assert_index_plan \
      "Gateway operation audit chronology scan" \
      "idx_qfg_operation_audit_recorded_at" \
      "SELECT audit_id FROM quickfix_gateway.gateway_operation_audit ORDER BY recorded_at_unix_ms, audit_id LIMIT 1"
    ;;
  *)
    echo "Unknown Flyway service: $service_name" >&2
    exit 1
    ;;
esac

#!/usr/bin/env bash

# Query-service evidence collectors. The caller owns namespace and run state.
query_postgres_json() {
  local sql="$1"
  local destination="$2"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1
  kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -c "$sql" >"$destination"
  jq -e . "$destination" >/dev/null
}

select_query_fixture() {
  query_postgres_json "
    SELECT json_build_object(
      'orderId', execution.order_id,
      'accountId', execution.account_id,
      'tradingDay', reference.trading_day,
      'venueMic', TRIM(execution.venue_mic),
      'symbol', execution.symbol
    )
    FROM query_service.execution_read_model execution
    JOIN query_service.order_read_model orders
      ON orders.order_id = execution.order_id
    JOIN query_service.account_summary_read_model account
      ON account.account_id = execution.account_id
    JOIN query_service.active_market_reference reference
      ON reference.venue_mic = execution.venue_mic
     AND reference.symbol = execution.symbol
    ORDER BY execution.executed_at_unix_ms DESC, execution.execution_id
    LIMIT 1;
  " "$evidence_dir/selected-fixture.json"
}

fetch_query_json() {
  local path="$1"
  local destination="$2"
  curl --connect-timeout 2 --max-time 20 --fail --silent --show-error \
    "http://127.0.0.1:${query_port}${path}" >"$destination"
  jq -e . "$destination" >/dev/null
}

capture_query_snapshot() {
  local destination="$1"
  local snapshot_dir
  snapshot_dir="$(mktemp -d "$evidence_dir/diagnostics/snapshot.XXXXXX")"
  fetch_query_json "/api/v1/orders/$order_id" "$snapshot_dir/order.json" || return 1
  fetch_query_json "/api/v1/orders/$order_id/executions" \
    "$snapshot_dir/executions.json" || return 1
  fetch_query_json "/api/v1/accounts/$account_id/summary" \
    "$snapshot_dir/account.json" || return 1
  fetch_query_json "/api/v1/market-reference/$trading_day/$venue_mic/$symbol" \
    "$snapshot_dir/market-reference.json" || return 1
  fetch_query_json "/api/v1/freshness" "$snapshot_dir/freshness.json" || return 1
  jq -n \
    --slurpfile order "$snapshot_dir/order.json" \
    --slurpfile executions "$snapshot_dir/executions.json" \
    --slurpfile accountSummary "$snapshot_dir/account.json" \
    --slurpfile marketReference "$snapshot_dir/market-reference.json" \
    --slurpfile freshness "$snapshot_dir/freshness.json" \
    '{order:$order[0],executions:$executions[0],accountSummary:$accountSummary[0],
      marketReference:$marketReference[0],freshness:$freshness[0]}' >"$destination"
  jq -e '.executions.data | length > 0' "$destination" >/dev/null
}

wait_for_query_snapshot() {
  local destination="$1"
  local attempt="$evidence_dir/diagnostics/rebuilt-attempt.json"
  for _ in $(seq 1 "$timeout_seconds"); do
    if query_consumer_group_caught_up \
        query-service-matching-events matching.events &&
      query_consumer_group_caught_up \
        query-service-account-lifecycle account.lifecycle &&
      capture_query_snapshot "$attempt" 2>/dev/null && jq -e '
      (.freshness.partitions | length) > 0
      and all(.freshness.partitions[]; .recoveryState == "READY")
    ' "$attempt" >/dev/null; then
      mv "$attempt" "$destination"
      return 0
    fi
    sleep 1
  done
  return 1
}

query_consumer_group_caught_up() {
  local group="$1"
  local topic="$2"
  local broker output
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || return 1
  output="$evidence_dir/diagnostics/${group}-status.txt"
  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka:9092 --group "$group" --describe >"$output" 2>&1 ||
    return 1
  awk -v topic="$topic" '
    $2 == topic {
      seen += 1
      if ($6 !~ /^[0-9]+$/ || $6 != 0) failed = 1
    }
    END { exit !(seen > 0 && failed == 0) }
  ' "$output"
}

reset_query_consumer_group() {
  local group="$1"
  local topic="$2"
  local broker
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || return 1
  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka:9092 --group "$group" --topic "$topic" \
    --reset-offsets --to-earliest --execute \
    >"$evidence_dir/${group}-offset-reset.txt" \
    2>"$evidence_dir/${group}-offset-reset.stderr"
}

query_redis_keys_present() {
  local keys=(
    "query:v1:order:$order_id"
    "query:v1:executions:$order_id"
    "query:v1:account-summary:$account_id"
    "query:v1:market-reference:$trading_day:$venue_mic:$symbol"
  )
  local key
  for key in "${keys[@]}"; do
    [[ "$(kns exec deployment/redis -- redis-cli EXISTS "$key" 2>/dev/null)" == 1 ]] ||
      return 1
  done
}

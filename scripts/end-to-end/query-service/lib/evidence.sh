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

wait_for_query_fixture() {
  local attempt="$evidence_dir/diagnostics/selected-fixture-attempt.json"
  for _ in $(seq 1 "$timeout_seconds"); do
    if select_query_fixture && jq -e '
      (.orderId | type == "string" and length > 0)
      and (.accountId | type == "string" and length > 0)
      and (.tradingDay | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))
      and (.venueMic | type == "string" and length == 4)
      and (.symbol | type == "string" and length > 0)
    ' "$evidence_dir/selected-fixture.json" >"$attempt" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
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

capture_query_replay_boundary() {
  local destination="$1"
  local matching_events_start="$destination/matching.events.start.json"
  local matching_events_end="$destination/matching.events.end.json"
  local account_lifecycle_start="$destination/account.lifecycle.start.json"
  local account_lifecycle_end="$destination/account.lifecycle.end.json"

  mkdir -p "$destination"
  capture_topic_offsets matching.events "$matching_events_start" \
    "$destination/matching.events.start.stderr.log" -2 || return 1
  capture_topic_offsets matching.events "$matching_events_end" \
    "$destination/matching.events.end.stderr.log" -1 || return 1
  capture_topic_offsets account.lifecycle "$account_lifecycle_start" \
    "$destination/account.lifecycle.start.stderr.log" -2 || return 1
  capture_topic_offsets account.lifecycle "$account_lifecycle_end" \
    "$destination/account.lifecycle.end.stderr.log" -1 || return 1

  jq -er '.partitions[] | "\(.partition)\t\(.offset)"' "$matching_events_end" \
    >"$destination/matching.events.targets.tsv"
  jq -er '.partitions[] | "\(.partition)\t\(.offset)"' "$account_lifecycle_end" \
    >"$destination/account.lifecycle.targets.tsv"
  jq -n \
    --arg capturedAtUtc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --slurpfile matchingEventsStart "$matching_events_start" \
    --slurpfile matchingEventsEnd "$matching_events_end" \
    --slurpfile accountLifecycleStart "$account_lifecycle_start" \
    --slurpfile accountLifecycleEnd "$account_lifecycle_end" \
    '{capturedAtUtc:$capturedAtUtc,topics:{matchingEvents:{start:$matchingEventsStart[0],
      end:$matchingEventsEnd[0]},accountLifecycle:{start:$accountLifecycleStart[0],
      end:$accountLifecycleEnd[0]}}}' \
    >"$destination/manifest.json"
}

write_query_consumer_reset_file() {
  local boundary_file="$1"
  local destination="$2"
  local topic

  topic="$(jq -er '.topic' "$boundary_file")" || return 1
  jq -er --arg topic "$topic" \
    '.partitions[] | "\($topic),\(.partition),\(.offset)"' "$boundary_file" \
    >"$destination"
  [[ "$(wc -l <"$destination")" -eq 15 ]]
}

wait_for_query_snapshot() {
  local destination="$1"
  local attempt="$evidence_dir/diagnostics/rebuilt-attempt.json"
  for _ in $(seq 1 "$timeout_seconds"); do
    if query_consumer_group_caught_up \
        query-service-matching-events matching.events \
        "$replay_boundary_dir/matching.events.targets.tsv" &&
      query_consumer_group_caught_up \
        query-service-account-lifecycle account.lifecycle \
        "$replay_boundary_dir/account.lifecycle.targets.tsv" &&
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
  local boundary_file="${3:-}"
  local broker output
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || return 1
  output="$evidence_dir/diagnostics/${group}-status.txt"
  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka:9092 --group "$group" --describe >"$output" 2>&1 ||
    return 1
  if [[ -n "$boundary_file" ]]; then
    [[ -s "$boundary_file" ]] || return 1
    awk -v topic="$topic" -v boundary_file="$boundary_file" '
      BEGIN {
        while ((getline line < boundary_file) > 0) {
          split(line, fields, "\t")
          if (fields[1] ~ /^[0-9]+$/ && fields[2] ~ /^[0-9]+$/) {
            target[fields[1]] = fields[2]
          }
        }
        close(boundary_file)
      }
      $2 == topic {
        seen += 1
        if ($3 !~ /^[0-9]+$/ || target[$3] !~ /^[0-9]+$/ ||
            $4 !~ /^[0-9]+$/ || ($4 + 0) != (target[$3] + 0)) failed = 1
      }
      END { exit !(seen == 15 && failed == 0) }
    ' "$output"
    return
  fi

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
  local boundary_file="${3:-}"
  local reset_file="$evidence_dir/${group}-offset-reset-input.txt"
  local remote_reset_file="/tmp/${group}-offsets.txt"
  local broker
  [[ -s "$boundary_file" ]] || return 1
  [[ "$group" =~ ^[a-z0-9-]+$ ]] || return 1
  [[ "$(jq -er '.topic' "$boundary_file")" == "$topic" ]] || return 1
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || return 1
  write_query_consumer_reset_file "$boundary_file" "$reset_file" || return 1
  kns exec -i "$broker" -c kafka -- sh -c "cat > '$remote_reset_file'" \
    <"$reset_file" || return 1
  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka:9092 --group "$group" \
    --reset-offsets --from-file "$remote_reset_file" --execute \
    >"$evidence_dir/${group}-offset-reset.txt" \
    2>"$evidence_dir/${group}-offset-reset.stderr" || return 1
  kns exec "$broker" -c kafka -- rm -f "$remote_reset_file" >/dev/null 2>&1 || true
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

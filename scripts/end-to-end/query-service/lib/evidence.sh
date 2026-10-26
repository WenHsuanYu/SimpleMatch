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
  local fixture_order fixture_account fixture_day fixture_venue fixture_symbol selectors
  # Select only this run's known public FIX order; never broaden grants for arbitrary database rows.
  selectors="$(jq -er -s \
    --slurpfile identities "${query_fixture_directory:?approved Query fixture bundle is required}/fixture-identities.json" '
      def canonical_uuid: type == "string" and
        test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
      select(length == 1 and ($identities | length) == 1) | .[0]
      | select(.fixtureType == "PUBLIC_FIX_CROSS")
      | select(.buy.accountId == $identities[0].accounts.X
          and .sell.accountId == $identities[0].accounts.Y
          and .buy.accountId != .sell.accountId)
      | select([.buy.accountId,.sell.accountId,.buy.orderId,.sell.orderId] | all(.[]; canonical_uuid))
      | select(.tradingDay | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))
      | select(.venueMic == "XTAI" or .venueMic == "ROCO")
      | select(.symbol | type == "string" and test("^[A-Z0-9]{1,12}$"))
      | [.buy.orderId,.buy.accountId,.tradingDay,.venueMic,.symbol] | @tsv
    ' "$evidence_dir/matching-fixture/fixture.json")" || return 1
  IFS=$'\t' read -r fixture_order fixture_account fixture_day fixture_venue fixture_symbol \
    <<<"$selectors" || return 1
  query_postgres_json "
    SELECT json_build_object(
      'orderId', execution.order_id,
      'accountId', execution.account_id,
      'tradingDay', reference.trading_day,
      'artifactId', reference.artifact_id,
      'venueMic', TRIM(execution.venue_mic),
      'symbol', execution.symbol
    )
    FROM query_service.execution_read_model execution
    JOIN query_service.order_read_model orders
      ON orders.order_id = execution.order_id
     AND orders.account_id = execution.account_id
     AND orders.venue_mic = execution.venue_mic
     AND orders.symbol = execution.symbol
    JOIN query_service.account_summary_read_model account
      ON account.account_id = execution.account_id
    JOIN query_service.active_market_reference reference
      ON reference.venue_mic = execution.venue_mic
     AND reference.symbol = execution.symbol
    WHERE execution.order_id = '$fixture_order'
      AND execution.account_id = '$fixture_account'
      AND execution.venue_mic = '$fixture_venue'
      AND execution.symbol = '$fixture_symbol'
      AND reference.trading_day = '$fixture_day'
    ORDER BY execution.executed_at_unix_ms DESC, execution.execution_id
    LIMIT 1;
  " "$evidence_dir/selected-fixture.json" || return 1
  jq -e -s 'length == 1 and (.[0] | type == "object")' \
    "$evidence_dir/selected-fixture.json" >/dev/null
}

capture_query_read_matrix() {
  local matrix_dir="$evidence_dir/read-matrix" pod selector expected
  mkdir -m 700 "$matrix_dir"
  jq -n --slurpfile selected "$evidence_dir/selected-fixture.json" \
    --slurpfile fixture "$evidence_dir/matching-fixture/fixture.json" \
    --arg missing "$(cat /proc/sys/kernel/random/uuid)" '
      {orders:[{account:"X",orderId:$fixture[0].buy.orderId,hasExecutions:true},
               {account:"Y",orderId:$fixture[0].sell.orderId,hasExecutions:true}],
       missingOrderId:$missing,
       market:($selected[0] | {tradingDay,venueMic,symbol,artifactId})}
    ' >"$matrix_dir/resources.json"
  chmod 600 "$matrix_dir/resources.json"
  local missing_order
  missing_order="$(jq -er '.missingOrderId' "$matrix_dir/resources.json")"
  query_postgres_json "SELECT json_build_object('count',count(*)) FROM query_service.order_read_model
    WHERE order_id='$missing_order';" "$matrix_dir/missing-parent.json" || return 1
  jq -e '.count == 0' "$matrix_dir/missing-parent.json" >/dev/null || return 1
  kns get deployment query-service -o json >"$matrix_dir/deployment.json"
  expected="$(jq -er '.spec.replicas | select(. >= 2)' "$matrix_dir/deployment.json")" || return 1
  selector="$(jq -r '.spec.selector.matchLabels | to_entries | map(.key+"="+.value) | join(",")' \
    "$matrix_dir/deployment.json")"
  kns get pods -l "$selector" -o json >"$matrix_dir/pods-before.json"
  jq -e --argjson expected "$expected" '
    (.items | length) == $expected and all(.items[];
      .metadata.deletionTimestamp == null and any(.status.conditions[]; .type == "Ready" and .status == "True"))
    ' "$matrix_dir/pods-before.json" >/dev/null || return 1
  stop_query_port_forward || return 1
  while IFS= read -r pod; do
    start_port_forward "pod/$pod" 8086 "$matrix_dir/$pod-forward.log" query_port_forward_pid query_port || {
      stop_query_port_forward
      return 1
    }
    if ! python3 "$script_dir/lib/read_matrix.py" --fixture-directory "$query_fixture_directory" \
      --resources "$matrix_dir/resources.json" --base-url "http://127.0.0.1:$query_port" \
      --policy-version 1 --profile initial --output "$matrix_dir/$pod.json"; then
      stop_query_port_forward
      return 1
    fi
    stop_query_port_forward || return 1
  done < <(jq -r '.items[].metadata.name' "$matrix_dir/pods-before.json")
  start_query_port_forward
  kns get pods -l "$selector" -o json >"$matrix_dir/pods-after.json"
  jq -e -s '([.[0].items[].metadata.uid] | sort) == ([.[1].items[].metadata.uid] | sort)' \
    "$matrix_dir/pods-before.json" "$matrix_dir/pods-after.json" >/dev/null || return 1
  jq -s --argjson expected "$expected" \
    'select(length == $expected and all(.[]; .status == "PASS")) |
     {status:"PASS",replicas:length,observations:(map(.observations|length)|add)}' \
    "$matrix_dir"/query-service-*.json >"$evidence_dir/read-matrix.json"
  jq -e '.status == "PASS"' "$evidence_dir/read-matrix.json" >/dev/null
}

seed_legacy_query_cache() {
  local key kind payload="$evidence_dir/diagnostics/legacy-cache-payload.json"
  mkdir -m 700 "$evidence_dir/legacy-cache"
  jq -n --slurpfile ids "$query_fixture_directory/fixture-identities.json" --arg order "$order_id" '
    {data:{orderId:$order,accountId:$ids[0].accounts.Y,artifactId:"legacy-cache-poison"},
     freshness:{partitions:[{sourceTopic:"legacy-cache-poison",partition:0,
       lastProcessedOffset:0,recoveryState:"READY",updatedAtUnixMs:1}]}}
    ' >"$payload"
  for kind in order executions account-summary market-reference; do
    case "$kind" in
      order|executions) key="query:v1:$kind:$order_id" ;;
      account-summary) key="query:v1:$kind:$account_id" ;;
      market-reference) key="query:v1:$kind:$trading_day:$venue_mic:$symbol" ;;
    esac
    kns exec -i deployment/redis -c redis -- redis-cli -x SET "$key" <"$payload" \
      >"$evidence_dir/legacy-cache/$kind-set.txt" || return 1
    kns exec deployment/redis -c redis -- redis-cli --raw GET "$key" \
      >"$evidence_dir/legacy-cache/$kind-before.json" || return 1
    jq -e '.freshness.partitions[0].sourceTopic == "legacy-cache-poison"' \
      "$evidence_dir/legacy-cache/$kind-before.json" >/dev/null || return 1
  done
}

verify_legacy_query_cache_unchanged() {
  local kind key
  for kind in order executions account-summary market-reference; do
    case "$kind" in
      order|executions) key="query:v1:$kind:$order_id" ;;
      account-summary) key="query:v1:$kind:$account_id" ;;
      market-reference) key="query:v1:$kind:$trading_day:$venue_mic:$symbol" ;;
    esac
    kns exec deployment/redis -c redis -- redis-cli --raw GET "$key" \
      >"$evidence_dir/legacy-cache/$kind-after.json" || return 1
    cmp "$evidence_dir/legacy-cache/$kind-before.json" "$evidence_dir/legacy-cache/$kind-after.json" || return 1
  done
  printf '%s\n' '{"status":"PASS","seededEntries":4,"unchangedAfterReads":true}' \
    >"$evidence_dir/legacy-cache.json"
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
  local module_dir
  module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  python3 "$module_dir/query_http.py" \
    --fixture-directory "${query_fixture_directory:?approved Query fixture bundle is required}" \
    --base-url "http://127.0.0.1:${query_port}" --path "$path" \
    --account-id "${account_id:-}" --output "$destination" >/dev/null
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

# Record restoration from the critical consumer observation contract.
write_query_restoration_evidence() {
  local matching_ready critical_ready
  matching_ready="$(matching_ready_replicas)" || return 1
  critical_ready=false
  if critical_consumer_state_is_healthy "$evidence_dir/critical-after.json"; then
    critical_ready=true
  fi
  jq -e '.queryPodCount == 0' "$evidence_dir/query-outage.json" >/dev/null ||
    return 1
  jq -n \
    --argjson matchingReady "$matching_ready" \
    --argjson criticalConsumersReady "$critical_ready" \
    --argjson queryOutageObserved true \
    '{queryServiceReady:true,queryOutageObserved:$queryOutageObserved,
      matchingReady:$matchingReady,criticalConsumersReady:$criticalConsumersReady}' \
    >"$evidence_dir/restoration.json"
}

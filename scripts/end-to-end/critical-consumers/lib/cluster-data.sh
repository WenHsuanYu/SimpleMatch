#!/usr/bin/env bash

# Kubernetes, Kafka, PostgreSQL, and test-fixture access for the deployed test.
# The caller provides context, namespace, evidence_dir, timeout_seconds, and repo_root.

kns() {
  kubectl --context "$context" -n "$namespace" "$@"
}

workload_replicas() {
  local kind="$1"
  local name="$2"
  kns get "$kind/$name" -o jsonpath='{.spec.replicas}'
}

wait_deployment_replicas() {
  local name="$1"
  local replicas="$2"
  if (( replicas == 0 )); then
    for _ in $(seq 1 "$timeout_seconds"); do
      local count
      count="$(kns get pods -l "app.kubernetes.io/name=$name" -o json | jq '.items | length')"
      (( count == 0 )) && return 0
      sleep 1
    done
    return 1
  fi
  kns rollout status "deployment/$name" --timeout="${timeout_seconds}s" >/dev/null
}

wait_statefulset_replicas() {
  local name="$1"
  local replicas="$2"
  if (( replicas == 0 )); then
    for _ in $(seq 1 "$timeout_seconds"); do
      local count
      count="$(kns get pods -l "app.kubernetes.io/name=$name" -o json | jq '.items | length')"
      (( count == 0 )) && return 0
      sleep 1
    done
    return 1
  fi
  kns rollout status "statefulset/$name" --timeout="${timeout_seconds}s" >/dev/null
}

scale_deployment() {
  local name="$1"
  local replicas="$2"
  kns scale "deployment/$name" --replicas="$replicas" >/dev/null || return 1
  wait_deployment_replicas "$name" "$replicas" || return 1
}

scale_statefulset() {
  local name="$1"
  local replicas="$2"
  kns scale "statefulset/$name" --replicas="$replicas" >/dev/null
  wait_statefulset_replicas "$name" "$replicas" ||
    die "statefulset/$name did not reach $replicas replicas"
}

postgres_pod() {
  kns get pods -l app.kubernetes.io/name=postgres -o json |
    jq -r '.items | if length == 1 then .[0].metadata.name else empty end'
}

kafka_pod() {
  kns get pods \
    -l 'app.kubernetes.io/name=kafka,app.kubernetes.io/component=broker' \
    -o json |
    jq -r '[.items[].metadata.name] | sort | .[0] // empty'
}

capture_topic_offsets() {
  local topic="$1"
  local destination="$2"
  local stderr_path="${3:-${destination%.json}.stderr.log}"
  local offset_time="${4:-}"
  local -a offset_args=(--bootstrap-server kafka:9092 --topic "$topic")
  local broker
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || return 1
  [[ -n "$offset_time" ]] && offset_args+=(--time "$offset_time")

  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-get-offsets.sh \
      "${offset_args[@]}" 2>"$stderr_path" |
    jq -eRn --arg topic "$topic" '
      [inputs
        | select(length > 0)
        | split(":")
        | {partition:(.[1] | tonumber), offset:(.[2] | tonumber)}]
      | sort_by(.partition)
      | if length == 15 and ([.[].partition] == [range(0; 15)]) then
          {topic:$topic, partitions:.}
        else
          error("topic offset snapshot must contain partitions 0 through 14")
        end
    ' >"$destination"
}

offset_for_partition() {
  local snapshot="$1"
  local partition="$2"
  jq -er --argjson partition "$partition" '
    [.partitions[] | select(.partition == $partition)] as $matches
    | if ($matches | length) == 1 then $matches[0].offset
      else error("topic partition offset must exist exactly once") end
    | select(type == "number" and . >= 0)
  ' "$snapshot"
}

capture_matching_committed_offsets() {
  local destination="$1"
  local raw_output="${destination%.json}.txt"
  local stderr_path="${destination%.json}.stderr.log"
  local broker
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || return 1

  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server kafka:9092 \
      --all-groups \
      --describe >"$raw_output" 2>"$stderr_path" || return 1

  normalize_matching_committed_offsets <"$raw_output" >"$destination" || return 1
  jq -e '
    (.partitions | length) == 15
    and ([.partitions[].partition] == [range(0; 15)])
    and all(.partitions[]; .committedOffset >= 0)
  ' "$destination" >/dev/null
}

capture_consumer_state() {
  local destination="$1"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1

  kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -c "
      SELECT json_build_object(
        'persistenceProgress', COALESCE((
          SELECT json_agg(row_to_json(p) ORDER BY p.partition_id)
          FROM (
            SELECT partition_id, last_processed_offset
            FROM persistence.matching_consumer_progress
            WHERE consumer_name = 'persistence-matching-events'
          ) p
        ), '[]'::json),
        'accountProgress', COALESCE((
          SELECT json_agg(row_to_json(a) ORDER BY a.partition_id)
          FROM (
            SELECT partition_id, last_processed_offset
            FROM account_service.matching_event_consumer_progress
            WHERE consumer_name = 'account-final-matching-events'
          ) a
        ), '[]'::json),
        'quickfixProgress', COALESCE((
          SELECT json_agg(row_to_json(q) ORDER BY q.partition_id)
          FROM (
            SELECT partition_id, last_processed_offset
            FROM quickfix_gateway.matching_consumer_progress
            WHERE consumer_name = 'quickfix-final-matching-events'
          ) q
        ), '[]'::json),
        'persistenceQuarantines', (
          SELECT COUNT(*) FROM persistence.matching_consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'accountQuarantines', (
          SELECT COUNT(*) FROM account_service.matching_event_consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'quickfixQuarantines', (
          SELECT COUNT(*) FROM quickfix_gateway.matching_consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'persistenceQuarantineHistory', (
          SELECT COUNT(*) FROM persistence.matching_consumer_quarantines
        ),
        'accountQuarantineHistory', (
          SELECT COUNT(*) FROM account_service.matching_event_consumer_quarantines
        ),
        'quickfixQuarantineHistory', (
          SELECT COUNT(*) FROM quickfix_gateway.matching_consumer_quarantines
        ),
        'quickfixPendingIntents', (
          SELECT COUNT(*) FROM quickfix_gateway.fix_delivery_intents
          WHERE status = 'PENDING'
        ),
        'admissionStateCounts', COALESCE((
          SELECT json_agg(row_to_json(admission) ORDER BY admission.state)
          FROM (
            SELECT state, COUNT(*) AS count
            FROM risk_service.admission_journal
            GROUP BY state
          ) admission
        ), '[]'::json),
        'riskQuarantines', (
          SELECT COUNT(*) FROM risk_service.consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'accountReservationStateCounts', COALESCE((
          SELECT json_agg(row_to_json(reservation) ORDER BY reservation.status)
          FROM (
            SELECT status, COUNT(*) AS count
            FROM account_service.account_reservations
            GROUP BY status
          ) reservation
        ), '[]'::json),
        'marketDataProgress', COALESCE((
          SELECT json_agg(row_to_json(market_data) ORDER BY market_data.partition_id)
          FROM (
            SELECT partition_id, recovery_state
            FROM market_data_projection.partition_projection_progress
          ) market_data
        ), '[]'::json),
        'marketDataInstrumentCount', (
          SELECT COUNT(*) FROM market_data_projection.instrument_market_data
        ),
        'marketDataDeadLetters', (
          SELECT COUNT(*) FROM market_data_projection.matching_event_dead_letters
        ),
        'activeMatchingOrders', (
          SELECT COUNT(*) FROM persistence.matching_order_projections
          WHERE status IN ('RESTING', 'PARTIALLY_FILLED')
        )
      )::text;
    " | jq -e . >"$destination"
}

capture_query_service_outage_state() {
  local destination="$1"
  kns get pods -l app.kubernetes.io/name=query-service -o json |
    jq '{queryPodCount:(.items | length), queryPodNames:[.items[].metadata.name]}' \
      >"$destination"
  jq -e '.queryPodCount == 0' "$destination" >/dev/null
}

require_clean_baseline() {
  local state="$1"
  jq -e '
    .persistenceQuarantines == 0
    and .accountQuarantines == 0
    and .quickfixQuarantines == 0
    and .persistenceQuarantineHistory == 0
    and .accountQuarantineHistory == 0
    and .quickfixQuarantineHistory == 0
    and .riskQuarantines == 0
    and .quickfixPendingIntents == 0
    and .marketDataDeadLetters == 0
    and .activeMatchingOrders == 0
  ' "$state" >/dev/null ||
    die 'baseline contains quarantine history, pending FIX delivery, or active Matching orders'
}

decode_configmap_file() {
  local configmap="$1"
  local key="$2"
  kns get "configmap/$configmap" -o json |
    jq -r --arg key "$key" '
      if .binaryData[$key] != null then
        .binaryData[$key] | @base64d
      elif .data[$key] != null then
        .data[$key]
      else
        empty
      end
    '
}

configured_quickfix_ingress_venue() {
  local application
  application="$(decode_configmap_file quickfix-gateway-config application.yaml)"
  [[ -n "$application" ]] ||
    die 'quickfix-gateway-config does not contain application.yaml'

  local venue
  venue="$(
    awk '
      /^[[:space:]]+venue-mic:[[:space:]]*/ {
        value = $0
        sub(/^[[:space:]]+venue-mic:[[:space:]]*/, "", value)
        sub(/[[:space:]]+$/, "", value)
        print value
        exit
      }
    ' <<<"$application"
  )"
  [[ "$venue" == "XTAI" || "$venue" == "ROCO" ]] ||
    die 'QuickFIX ingress venue must be explicitly configured as XTAI or ROCO'
  printf '%s\n' "$venue"
}

current_taipei_calendar_day() {
  TZ=Asia/Taipei date +%F
}

expected_fix_trading_day() {
  local requested="${SIMPLEMATCH_CERTIFICATION_TRADING_DAY:-}"
  if [[ -z "$requested" ]]; then
    current_taipei_calendar_day
    return
  fi
  if [[ ! "$requested" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
    die "SIMPLEMATCH_CERTIFICATION_TRADING_DAY must use YYYY-MM-DD: $requested"
    return 1
  fi
  printf '%s\n' "$requested"
}

require_live_fix_trading_day() {
  local configured_trading_day="$1"
  local expected_trading_day
  expected_trading_day="$(expected_fix_trading_day)" || return 1

  if [[ "$configured_trading_day" != "$expected_trading_day" ]]; then
    die "retained namespace trading day $configured_trading_day does not match expected certification trading day $expected_trading_day"
    return 1
  fi
}

select_market_input() {
  if [[ "$(kns get configmap matching-session-config -o jsonpath='{.immutable}')" != "true" ]]; then
    die 'matching-session-config must be immutable'
    return 1
  fi
  artifact_json="$(decode_configmap_file matching-daily-artifact market_reference.json)"
  artifact_checksum="$(decode_configmap_file matching-daily-artifact market_reference.sha256 | tr -d '\r\n')"
  [[ -n "$artifact_json" ]] || die 'matching-daily-artifact does not contain market_reference.json'
  [[ "$artifact_checksum" =~ ^[0-9a-f]{64}$ ]] ||
    die 'matching-daily-artifact does not contain a valid market_reference.sha256'

  trading_day="$(kns get configmap matching-session-config -o jsonpath='{.data.trading_day}')"
  trading_session_id="$(kns get configmap matching-session-config -o jsonpath='{.data.trading_session_id}')"
  matching_image_identity="$(kns get configmap matching-session-config -o jsonpath='{.data.matching_image_digest}')"
  [[ "$trading_day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] ||
    die 'matching-session-config trading_day is invalid'
  [[ -n "$trading_session_id" ]] || die 'matching-session-config trading_session_id is missing'
  [[ -n "$matching_image_identity" ]] || die 'matching-session-config matching_image_digest is missing'
  [[ "$(jq -r '.metadata.tradingDay' <<<"$artifact_json")" == "$trading_day" ]] ||
    die 'Market Reference trading day does not match matching-session-config'
  require_live_fix_trading_day "$trading_day"

  routing_algorithm_version="$(jq -r '.metadata.routingAlgorithmVersion // empty' <<<"$artifact_json")"
  [[ -n "$routing_algorithm_version" ]] || die 'Market Reference routing algorithm version is missing'
  artifact_id="market-reference-$trading_day"
  gateway_venue_mic="$(configured_quickfix_ingress_venue)"

  selected_instrument="$(
    jq -c --arg venue_mic "$gateway_venue_mic" '
      [.marketSnapshot.instruments[]
       | select(.venueMic == $venue_mic)
       | select(.eligibility == "ELIGIBLE")
       | select(.referencePriceUnits != null)]
      | sort_by(.symbol)
      | .[0] // empty
    ' <<<"$artifact_json"
  )"
  [[ -n "$selected_instrument" ]] ||
    die "Market Reference contains no eligible priced instrument for QuickFIX ingress venue $gateway_venue_mic"
  venue_mic="$(jq -r '.venueMic // empty' <<<"$selected_instrument")"
  symbol="$(jq -r '.symbol // empty' <<<"$selected_instrument")"
  price_units="$(jq -r '.referencePriceUnits' <<<"$selected_instrument")"
  rule_id="$(jq -r '.marketRuleId // empty' <<<"$selected_instrument")"
  [[ "$venue_mic" == "$gateway_venue_mic" ]] ||
    die 'selected instrument venue does not match the QuickFIX ingress venue'
  [[ -n "$symbol" ]] || die 'selected symbol is invalid'
  [[ -n "$rule_id" ]] || die 'selected market rule is invalid'

  quantity="$(
    jq -r --arg rule_id "$rule_id" '
      .marketRules.rules[]
      | select(.ruleId == $rule_id)
      | .boardLotShares
    ' <<<"$artifact_json" | head -n 1
  )"
  [[ "$price_units" =~ ^[1-9][0-9]*$ ]] || die 'selected price units are invalid'
  [[ "$quantity" =~ ^[1-9][0-9]*$ ]] || die 'selected board lot quantity is invalid'
  price="$(( price_units / 10000 )).$(printf '%04d' "$(( price_units % 10000 ))")"
  printf '%s\n' "$selected_instrument" | jq . >"$evidence_dir/submission/selected-instrument.json"
}

seed_account_limit() {
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || die 'cannot resolve PostgreSQL Pod for account fixture'
  local now_ms
  now_ms="$(( $(date +%s) * 1000 ))"
  kns exec -i "$postgres" -c postgres -- psql -U simplematch -d simplematch \
    -v ON_ERROR_STOP=1 >"$evidence_dir/submission/account-fixture.log" 2>&1 <<SQL
INSERT INTO account_service.account_limits (
  account_id, scope_type, scope_key, trading_day, currency,
  limit_total_notional, reserved_notional, utilized_notional,
  available_notional, updated_at_unix_ms, version
) VALUES (
  '$account_id', 'ACCOUNT', '*', DATE '$trading_day', 'TWD',
  99999999999999999999.00000000, 0, 0,
  99999999999999999999.00000000, $now_ms, 0
);
SQL
}

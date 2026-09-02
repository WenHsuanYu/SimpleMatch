#!/usr/bin/env bash

# Public event liveness probe used while query-service is scaled to zero.
# The caller provides the shared critical-consumer interfaces and run state.

query_active_submit_open_eligible_observation() {
  local check="$1"
  local observation="$evidence_dir/baseline/gateway-observation-query-active-$check.json"
  local final_response="$evidence_dir/baseline/gateway-observation-query-active-$check-response.json"

  for gateway_attempt in 1 2 3; do
    local response
    response="$evidence_dir/baseline/gateway-observation-query-active-${check}-gateway-attempt-${gateway_attempt}.json"
    capture_gateway_observation "query-active-$check" "$observation" || return 1
    gateway_request POST /operations/observations "$response" "$observation" || return 1
    if jq -e '.readiness == "OPEN_ELIGIBLE"' "$response" >/dev/null; then
      cp "$response" "$final_response"
      return 0
    fi
    if gateway_response_is_retryable_stale "$response"; then
      sleep 0.2
      continue
    fi
    jq . "$response" >&2
    return 1
  done
  return 1
}

open_query_active_gateway() {
  local open_request="$evidence_dir/active-liveness/gateway-open-request.json"
  local open_response="$evidence_dir/active-liveness/gateway-open-response.json"

  for check in 1 2 3; do
    query_active_submit_open_eligible_observation "$check" || return 1
    sleep 0.2
  done

  jq -n '{actor:"query-service-certification",reason:"active processing liveness probe"}' \
    >"$open_request"
  gateway_request POST /operations/open "$open_response" "$open_request" || return 1
  jq -e '.accepted == true and .gateState == "OPEN" and (.occurredAt | type == "string")' \
    "$open_response" >/dev/null
}

capture_query_active_account_reservation() {
  local selected_order_id="$1"
  local destination="$2"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1

  local reservation
  reservation="$(
    kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
      -v ON_ERROR_STOP=1 -c "
        SELECT json_build_object(
          'orderId', order_id,
          'accountId', account_id,
          'status', status,
          'remainingQuantity', remaining_quantity,
          'filledQuantity', filled_quantity,
          'reservedNotional', reserved_notional
        )::text
        FROM account_service.account_reservations
        WHERE order_id = '$selected_order_id';
      "
  )" || return 1
  [[ -n "$reservation" ]] || return 1
  printf '%s\n' "$reservation" | jq -e . >"$destination"
}

wait_query_active_account_reservation() {
  local selected_order_id="$1"
  local destination="$2"
  for _ in $(seq 1 "$timeout_seconds"); do
    if capture_query_active_account_reservation "$selected_order_id" "$destination" \
        && jq -e '
          .status == "RESERVATION_STATUS_RELEASED"
          and .remainingQuantity == 0
          and .reservedNotional == 0
        ' "$destination" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

prepare_query_active_liveness() {
  active_liveness_evidence_dir="$evidence_dir/active-liveness"
  mkdir -p \
    "$active_liveness_evidence_dir" \
    "$active_liveness_evidence_dir/fix" \
    "$active_liveness_evidence_dir/observer" \
    "$evidence_dir/baseline" \
    "$evidence_dir/fix" \
    "$evidence_dir/outage" \
    "$evidence_dir/submission"

  active_liveness_previous_account_id="${account_id:-}"
  active_liveness_previous_cl_ord_id="${cl_ord_id:-}"
  active_liveness_previous_trading_day="${trading_day:-}"
  active_liveness_previous_venue_mic="${venue_mic:-}"
  active_liveness_previous_symbol="${symbol:-}"
  active_liveness_previous_quantity="${quantity:-}"
  active_liveness_previous_price="${price:-}"
  active_liveness_originals_saved=true

  local fixture_trading_day="$trading_day"
  local fixture_venue_mic="$venue_mic"
  local fixture_symbol="$symbol"
  select_market_input
  [[ "$trading_day" == "$fixture_trading_day" ]] ||
    die 'active liveness selected a different trading day from the query fixture'
  [[ "$venue_mic" == "$fixture_venue_mic" ]] ||
    die 'active liveness selected a different venue from the query fixture'
  [[ "$symbol" == "$fixture_symbol" ]] ||
    die 'active liveness selected a different instrument from the query fixture'

  active_liveness_account_id="$(cat /proc/sys/kernel/random/uuid)"
  active_liveness_cl_ord_id="QUERY-LIVE-$(date -u +%Y%m%d-%H%M%S)-$$"
  account_id="$active_liveness_account_id"
  cl_ord_id="$active_liveness_cl_ord_id"
  seed_account_limit "$active_liveness_evidence_dir/account-fixture.log"

  live_fix_time_in_force=3
  observer_pod="matching-event-outage-observer"
  observer_manifest="$repo_root/deploy/k8s/verification/matching-event-observer-pod.yaml"
  observer_created=false
  kafka_observer_pod="critical-consumer-kafka-observer"
  kafka_observer_manifest="$repo_root/deploy/k8s/verification/critical-consumer-kafka-observer-pod.yaml"
  kafka_observer_port_forward_pid=""
  kafka_observer_port=""
  kafka_observer_created=false
  kafka_observer_manifest_prepared=false
  matching_event_observer_manifest_prepared=false

  enable_gateway_operations
  start_fix_port_forward
  start_fix_submit_client || die 'active liveness FIX client did not reach its barrier'
  start_gateway_port_forward
  start_kafka_observation_adapter "$retained_evidence_dir"
  open_query_active_gateway || die 'Gateway did not open for active liveness probe'
  capture_kafka_matching_events_end_positions \
    "$active_liveness_evidence_dir/matching-events-start.json" ||
    die 'cannot capture Matching Event offset before active liveness release'
  active_liveness_prepared=true
}

run_query_active_liveness() {
  [[ "${active_liveness_prepared:-false}" == true ]] ||
    die 'active liveness was not prepared before release'
  jq -e '.queryPodCount == 0' "$evidence_dir/query-outage.json" >/dev/null ||
    die 'active liveness release requires query-service to have zero Pods'

  active_liveness_started_epoch_ms="$(date +%s%3N)"
  release_fix_submit_client
  wait_fix_submit_client || die 'active liveness FIX submission client failed'
  cp "$evidence_dir/fix/submit.json" \
    "$active_liveness_evidence_dir/fix/submit.json"

  capture_risk_admission "$active_liveness_evidence_dir/risk-admission.json"
  active_liveness_command_id="$(jq -er '.commandId' \
    "$active_liveness_evidence_dir/risk-admission.json")"
  active_liveness_order_id="$(jq -er '.orderId' \
    "$active_liveness_evidence_dir/risk-admission.json")"
  active_liveness_partition="$(jq -er '.routingPartition | numbers' \
    "$active_liveness_evidence_dir/risk-admission.json")"
  active_liveness_start_offset="$(jq -er --argjson partition "$active_liveness_partition" '
    [.partitions[] | select(.partition == $partition)] as $matches
    | if ($matches | length) == 1 then $matches[0].offset
      else error("active Matching Event partition offset is missing") end
    | select(type == "number" and . >= 0)
  ' "$active_liveness_evidence_dir/matching-events-start.json")"

  create_observer_pod
  mkdir -p "$evidence_dir/outage"
  run_event_observer \
    "$active_liveness_partition" \
    "$active_liveness_start_offset" \
    "$active_liveness_command_id" \
    "$active_liveness_order_id"
  cp "$evidence_dir/outage/matching-event-observation.json" \
    "$active_liveness_evidence_dir/observer/matching-event-observation.json"
  cp "$evidence_dir/outage/matching-event-observer-verdict.json" \
    "$active_liveness_evidence_dir/observer/matching-event-observer-verdict.json"

  wait_order_projection_status \
    "$active_liveness_order_id" CANCELLED \
    "$active_liveness_evidence_dir/order-projection.json" ||
    die 'active IOC order did not reach a terminal Persistence projection'
  wait_query_active_account_reservation \
    "$active_liveness_order_id" \
    "$active_liveness_evidence_dir/account-reservation.json" ||
    die 'active IOC account reservation did not reach RELEASED'
  active_liveness_event_offset="$(jq -er '.offset | numbers' \
    "$active_liveness_evidence_dir/observer/matching-event-observation.json")"
  active_liveness_event_id="$(jq -er '.eventId' \
    "$active_liveness_evidence_dir/observer/matching-event-observation.json")"
  wait_consumers_through "$active_liveness_partition" "$active_liveness_event_offset" ||
    die 'critical consumers did not process the active Matching Event'
  wait_market_data_through \
    "$active_liveness_partition" "$active_liveness_event_offset" \
    "$active_liveness_evidence_dir/market-data-progress.json" ||
    die 'market-data projection did not process the active Matching Event'
  require_exact_event_once_with_market_data \
    "$active_liveness_event_id" \
    "$active_liveness_evidence_dir/exact-inbox-counts.json"
  wait_fix_intent_status SENT "$active_liveness_evidence_dir/fix-intent.json" ||
    die 'QuickFIX did not durably deliver the active terminal event'
  capture_consumer_state "$active_liveness_evidence_dir/consumer-state.json" ||
    die 'cannot capture active liveness consumer state'
  jq -e '
    .persistenceQuarantines == 0
    and .accountQuarantines == 0
    and .quickfixQuarantines == 0
    and .quickfixPendingIntents == 0
    and .activeMatchingOrders == 0
    and .marketDataDeadLetters == 0
  ' "$active_liveness_evidence_dir/consumer-state.json" >/dev/null ||
    die 'active liveness left quarantine, pending, active-order, or market-data failure state'

  active_liveness_completed_epoch_ms="$(date +%s%3N)"
  jq -n \
    --argjson startedAtEpochMs "$active_liveness_started_epoch_ms" \
    --argjson completedAtEpochMs "$active_liveness_completed_epoch_ms" \
    --argjson timeoutSeconds "$timeout_seconds" \
    --slurpfile queryOutage "$evidence_dir/query-outage.json" \
    --slurpfile gatewayOpen "$active_liveness_evidence_dir/gateway-open-response.json" \
    --slurpfile fixSubmission "$active_liveness_evidence_dir/fix/submit.json" \
    --slurpfile riskAdmission "$active_liveness_evidence_dir/risk-admission.json" \
    --slurpfile matchingEvent \
      "$active_liveness_evidence_dir/observer/matching-event-observation.json" \
    --slurpfile observerVerdict \
      "$active_liveness_evidence_dir/observer/matching-event-observer-verdict.json" \
    --slurpfile orderProjection "$active_liveness_evidence_dir/order-projection.json" \
    --slurpfile accountReservation \
      "$active_liveness_evidence_dir/account-reservation.json" \
    --slurpfile marketData "$active_liveness_evidence_dir/market-data-progress.json" \
    --slurpfile fixIntent "$active_liveness_evidence_dir/fix-intent.json" \
    --slurpfile consumerState "$active_liveness_evidence_dir/consumer-state.json" \
    --slurpfile exactInboxCounts "$active_liveness_evidence_dir/exact-inbox-counts.json" '
      {
        status:"PROVEN",
        startedAtEpochMs:$startedAtEpochMs,
        completedAtEpochMs:$completedAtEpochMs,
        elapsedMilliseconds:($completedAtEpochMs - $startedAtEpochMs),
        timeoutSeconds:$timeoutSeconds,
        queryOutage:$queryOutage[0],
        gatewayOpen:$gatewayOpen[0],
        timeInForce:"3",
        fixSubmission:$fixSubmission[0],
        riskAdmission:$riskAdmission[0],
        matchingEvent:$matchingEvent[0],
        observerVerdict:$observerVerdict[0],
        orderProjection:$orderProjection[0],
        accountReservation:$accountReservation[0],
        marketData:($marketData[0] + {inboxCount:$exactInboxCounts[0].marketData}),
        fixDeliveryIntent:$fixIntent[0],
        consumerState:$consumerState[0],
        exactInboxCounts:$exactInboxCounts[0]
      }
    ' >"$evidence_dir/active-processing-liveness.json" ||
    die 'active liveness evidence could not be serialized'
}

restore_query_active_liveness() {
  set +e
  stop_background_process "${fix_submit_pid:-}"
  fix_submit_pid=""
  stop_fix_port_forward
  stop_gateway_port_forward
  stop_kafka_observation_adapter
  if [[ "${observer_created:-false}" == true ]]; then
    kns delete pod "$observer_pod" --ignore-not-found \
      --wait=true --timeout="${timeout_seconds}s" >/dev/null 2>&1 ||
      restoration_failed=true
    observer_created=false
  fi
  delete_kafka_observer_pod || restoration_failed=true
  restore_gateway_environment

  if [[ "${active_liveness_originals_saved:-false}" == true ]]; then
    account_id="$active_liveness_previous_account_id"
    cl_ord_id="$active_liveness_previous_cl_ord_id"
    trading_day="$active_liveness_previous_trading_day"
    venue_mic="$active_liveness_previous_venue_mic"
    symbol="$active_liveness_previous_symbol"
    quantity="$active_liveness_previous_quantity"
    price="$active_liveness_previous_price"
    active_liveness_originals_saved=false
  fi
  live_fix_time_in_force=0
  active_liveness_prepared=false
  set -e
}

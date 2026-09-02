#!/usr/bin/env bash

# Public FIX cross-order fixture used to populate execution-backed query read models.
# The caller provides the shared cluster, Gateway, FIX, and PostgreSQL interfaces.

query_fixture_wait_account_reservation_status() {
  local selected_order_id="$1"
  local expected_status="$2"
  local destination="$3"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    local postgres
    postgres="$(postgres_pod)"
    if [[ -n "$postgres" ]]; then
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
          " 2>/dev/null
      )" || true
      if [[ -n "$reservation" ]] && printf '%s\n' "$reservation" | jq . >"$destination" \
          2>/dev/null && jq -e --arg expected "$expected_status" \
            '.status == $expected' "$destination" >/dev/null 2>&1; then
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}

query_fixture_submit_order() {
  local side="$1"
  local fixture_account_id="$2"
  local long_quantity="$3"
  local phase_dir="$4"
  local expected_projection_status="$5"
  local fix_side=1
  local expected_active_matching_orders=0
  [[ "$side" == BUY || "$side" == SELL ]] || return 1
  [[ "$side" == SELL ]] && fix_side=2
  [[ "$side" == SELL ]] && expected_active_matching_orders=1

  evidence_dir="$phase_dir"
  mkdir -p "$phase_dir/fix" "$phase_dir/submission"
  account_id="$fixture_account_id"
  cl_ord_id="QUERY-FIX-${side}-$(date -u +%Y%m%d-%H%M%S)-$$"
  live_fix_side="$fix_side"
  live_fix_time_in_force=0

  if [[ "$side" == BUY ]]; then
    seed_account_limit "$phase_dir/account-limit-fixture.log" || return 1
  fi
  seed_account_position "$phase_dir/account-position-fixture.log" "$long_quantity" || return 1

  # Keep the FIX client at its barrier while the Gateway observes fresh readiness. The
  # production safety monitor pauses new orders once the latest observation is stale.
  start_fix_submit_client || return 1
  open_gateway_from_fresh_observations \
    "query-fixture-$side" open "execution-backed $side query fixture" \
    "$expected_active_matching_orders" || return 1
  release_fix_submit_client
  wait_fix_submit_client || return 1
  capture_risk_admission "$phase_dir/risk-admission.json" || return 1

  query_fixture_last_order_id="$(jq -er '.orderId' "$phase_dir/risk-admission.json")" || return 1
  query_fixture_last_cl_ord_id="$cl_ord_id"
  wait_order_projection_status \
    "$query_fixture_last_order_id" \
    "$expected_projection_status" \
    "$phase_dir/order-projection.json" || return 1
  wait_fix_intent_status SENT "$phase_dir/fix-intent.json" || return 1

  jq -n \
    --arg side "$side" \
    --arg accountId "$account_id" \
    --arg clOrdId "$cl_ord_id" \
    --arg orderId "$query_fixture_last_order_id" \
    --argjson fixSide "$fix_side" \
    '{side:$side,fixSide:$fixSide,accountId:$accountId,clOrdId:$clOrdId,orderId:$orderId}' \
    >"$phase_dir/order.json"
}

query_fixture_write_critical_state() {
  local destination="$1"
  capture_consumer_state "$destination" || return 1
  require_clean_baseline "$destination"
}

query_fixture_run_impl() {
  local fixture_dir="$1"
  local buy_account_id
  local sell_account_id
  local buy_order_id
  local sell_order_id
  local buy_cl_ord_id
  local sell_cl_ord_id

  mkdir -p "$fixture_dir/baseline" "$fixture_dir/submission"
  evidence_dir="$fixture_dir"
  select_market_input || return 1

  buy_account_id="$(cat /proc/sys/kernel/random/uuid)" || return 1
  sell_account_id="$(cat /proc/sys/kernel/random/uuid)" || return 1

  kafka_observer_pod="critical-consumer-kafka-observer"
  kafka_observer_manifest="$repo_root/deploy/k8s/verification/critical-consumer-kafka-observer-pod.yaml"
  kafka_observer_port_forward_pid=""
  kafka_observer_port=""
  kafka_observer_created=false
  kafka_observer_manifest_prepared=false

  enable_gateway_operations || return 1
  start_fix_port_forward || return 1
  start_gateway_port_forward || return 1
  start_kafka_observation_adapter "$retained_evidence_dir" || return 1

  query_fixture_submit_order BUY "$buy_account_id" 0 \
    "$fixture_dir/buy" RESTING || return 1
  buy_order_id="$query_fixture_last_order_id"
  buy_cl_ord_id="$query_fixture_last_cl_ord_id"

  sleep 1
  query_fixture_submit_order SELL "$sell_account_id" "$quantity" \
    "$fixture_dir/sell" FILLED || return 1
  sell_order_id="$query_fixture_last_order_id"
  sell_cl_ord_id="$query_fixture_last_cl_ord_id"

  evidence_dir="$fixture_dir"
  wait_order_projection_status \
    "$buy_order_id" FILLED \
    "$fixture_dir/buy/trade-order-projection.json" || return 1
  query_fixture_wait_account_reservation_status \
    "$buy_order_id" RESERVATION_STATUS_APPLIED \
    "$fixture_dir/buy/account-reservation.json" || return 1
  query_fixture_wait_account_reservation_status \
    "$sell_order_id" RESERVATION_STATUS_APPLIED \
    "$fixture_dir/sell/account-reservation.json" || return 1

  cl_ord_id="$buy_cl_ord_id"
  wait_fix_intent_status SENT "$fixture_dir/buy/trade-fix-intent.json" || return 1
  cl_ord_id="$sell_cl_ord_id"
  wait_fix_intent_status SENT "$fixture_dir/sell/trade-fix-intent.json" || return 1
  query_fixture_write_critical_state "$fixture_dir/critical-consumer-state.json" || return 1

  jq -n \
    --arg tradingDay "$trading_day" \
    --arg venueMic "$venue_mic" \
    --arg symbol "$symbol" \
    --arg quantity "$quantity" \
    --arg price "$price" \
    --arg buyAccountId "$buy_account_id" \
    --arg buyClOrdId "$buy_cl_ord_id" \
    --arg buyOrderId "$buy_order_id" \
    --arg sellAccountId "$sell_account_id" \
    --arg sellClOrdId "$sell_cl_ord_id" \
    --arg sellOrderId "$sell_order_id" \
    '{fixtureType:"PUBLIC_FIX_CROSS",tradingDay:$tradingDay,venueMic:$venueMic,symbol:$symbol,
      quantity:$quantity,price:$price,buy:{accountId:$buyAccountId,clOrdId:$buyClOrdId,
      orderId:$buyOrderId},sell:{accountId:$sellAccountId,clOrdId:$sellClOrdId,
      orderId:$sellOrderId}}' >"$fixture_dir/fixture.json"
}

cleanup_query_execution_fixture() {
  set +e
  stop_background_process "${fix_submit_pid:-}"
  fix_submit_pid=""
  stop_fix_port_forward
  stop_gateway_port_forward
  stop_kafka_observation_adapter
  delete_kafka_observer_pod || restoration_failed=true
  restore_gateway_environment
  set -e
}

run_query_execution_fixture() {
  local original_evidence_dir="$evidence_dir"
  local original_account_id="${account_id:-}"
  local original_cl_ord_id="${cl_ord_id:-}"
  local original_live_fix_side="${live_fix_side:-1}"
  local original_live_fix_time_in_force="${live_fix_time_in_force:-0}"
  local fixture_dir="$original_evidence_dir/matching-fixture"
  local status=0

  if query_fixture_run_impl "$fixture_dir"; then
    status=0
  else
    status=$?
  fi
  cleanup_query_execution_fixture
  if [[ "$restoration_failed" == true && "$status" -eq 0 ]]; then
    status=1
  fi

  evidence_dir="$original_evidence_dir"
  account_id="$original_account_id"
  cl_ord_id="$original_cl_ord_id"
  live_fix_side="$original_live_fix_side"
  live_fix_time_in_force="$original_live_fix_time_in_force"
  return "$status"
}

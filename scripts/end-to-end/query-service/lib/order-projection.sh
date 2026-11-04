#!/usr/bin/env bash

# Read a durable Persistence projection for the retained Query fixture.
# The caller owns the namespace, account and bounded test deadline.

capture_order_projection() {
  local selected_order_id="$1"
  local destination="$2"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1

  local projection
  projection="$(
    kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
      -v ON_ERROR_STOP=1 -c "
        SELECT json_build_object(
          'orderId', order_id::text,
          'status', status,
          'cumulativeQuantityShares', cumulative_quantity_shares,
          'leavesQuantityShares', leaves_quantity_shares,
          'lastEventId', encode(last_event_id, 'hex')
        )::text
        FROM persistence.matching_order_projections
        WHERE order_id = '$selected_order_id';
      " 2>/dev/null
  )" || return 1
  [[ -n "$projection" ]] || return 1
  printf '%s\n' "$projection" | jq . >"$destination"
}

wait_order_projection_status() {
  local selected_order_id="$1"
  local expected="$2"
  local destination="$3"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    if capture_order_projection "$selected_order_id" "$destination" \
        && jq -e --arg expected "$expected" '.status == $expected' \
          "$destination" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
runner="$script_dir/../run-certification.sh"
active_module="$script_dir/../lib/active-liveness.sh"
temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

python3 "$script_dir/../provision-security-fixtures.py" "$temporary_directory/query-bundle" >/dev/null
if ! (
  source "$active_module"
  evidence_dir="$temporary_directory/approved-active"
  query_fixture_directory="$temporary_directory/query-bundle"
  expected_account="$(jq -er '.accounts.Y' "$query_fixture_directory/fixture-identities.json")"
  account_id="$(jq -er '.accounts.X' "$query_fixture_directory/fixture-identities.json")"
  trading_day=2026-08-11 venue_mic=XTAI symbol=2330 quantity=100 price=100
  select_market_input() { :; }
  seed_account_limit() {
    [[ "$account_id" == "$expected_account" ]] || {
      printf '%s\n' 'Active IOC must use the pre-approved Y account, leaving baseline X unchanged.' >&2
      exit 1
    }
    exit 0  # Stop at the external seed interface; do not claim a FIX/runtime test.
  }
  prepare_query_active_liveness
  exit 1
); then
  printf '%s\n' 'Active liveness selected an unapproved account.' >&2
  exit 1
fi

active_function_start="$(grep -n '^run_query_active_liveness()' "$active_module" | cut -d: -f1)"
active_function_end="$(grep -n '^restore_query_active_liveness()' "$active_module" | cut -d: -f1)"
active_function_body="$temporary_directory/active-liveness-body.sh"
sed -n "${active_function_start},$((active_function_end - 1))p" \
  "$active_module" >"$active_function_body"
fresh_open_line="$(grep -n 'open_query_active_gateway' "$active_function_body" | head -n 1 | cut -d: -f1)"
release_line="$(grep -n 'release_fix_submit_client' "$active_function_body" | head -n 1 | cut -d: -f1)"
(( fresh_open_line > 0 && release_line > fresh_open_line )) || {
  printf '%s\n' 'Active liveness must refresh Gateway observations before FIX release.' >&2
  exit 1
}

prepare_line="$(grep -n '^prepare_query_active_liveness$' "$runner" | cut -d: -f1)"
scale_zero_line="$(grep -n 'scale_deployment query-service 0' "$runner" | head -n 1 | cut -d: -f1)"
active_line="$(grep -n '^run_query_active_liveness$' "$runner" | cut -d: -f1)"
restore_active_line="$(grep -n '^restore_query_active_liveness$' "$runner" | cut -d: -f1)"
(( prepare_line < scale_zero_line && scale_zero_line < active_line && active_line < restore_active_line )) || {
  printf '%s\n' 'Active liveness must be prepared before and released during the query outage.' >&2
  exit 1
}

(
  source "$active_module"
  timeout_seconds=5
  evidence_dir="$temporary_directory/evidence"
  mkdir -p "$evidence_dir"
  restoration_failed=false
  fix_submit_pid=123
  observer_pod=query-active-observer
  observer_created=true
  kafka_observer_created=true
  gateway_env_modified=true
  active_liveness_originals_saved=true
  active_liveness_previous_account_id=account-before
  active_liveness_previous_cl_ord_id=clord-before
  active_liveness_previous_trading_day=day-before
  active_liveness_previous_venue_mic=venue-before
  active_liveness_previous_symbol=symbol-before
  active_liveness_previous_quantity=10
  active_liveness_previous_price=1.0000
  account_id=account-active
  cl_ord_id=clord-active
  trading_day=day-active
  venue_mic=venue-active
  symbol=symbol-active
  quantity=20
  price=2.0000
  calls="$temporary_directory/cleanup.calls"
  : >"$calls"
  stop_background_process() { printf 'background\n' >>"$calls"; }
  stop_fix_port_forward() { printf 'fix\n' >>"$calls"; }
  stop_gateway_port_forward() { printf 'gateway\n' >>"$calls"; }
  stop_kafka_observation_adapter() { printf 'kafka-stop\n' >>"$calls"; }
  delete_kafka_observer_pod() { printf 'kafka-delete\n' >>"$calls"; kafka_observer_created=false; }
  restore_gateway_environment() { printf 'gateway-restore\n' >>"$calls"; gateway_env_modified=false; }
  kns() { printf 'observer-delete\n' >>"$calls"; }
  restore_query_active_liveness
  [[ "$account_id" == account-before && "$cl_ord_id" == clord-before ]] || exit 1
  [[ "$trading_day" == day-before && "$venue_mic" == venue-before ]] || exit 1
  [[ "$symbol" == symbol-before && "$quantity" == 10 && "$price" == 1.0000 ]] || exit 1
  [[ "$observer_created" == false && "$kafka_observer_created" == false ]] || exit 1
  grep -Fxq background "$calls"
  grep -Fxq observer-delete "$calls"
  grep -Fxq kafka-delete "$calls"
  grep -Fxq gateway-restore "$calls"
)

printf 'Query-service active liveness contract is valid.\n'

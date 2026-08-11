#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/flyway-services.sh
source "$repo_root/scripts/lib/flyway-services.sh"

expected_services=(
  account-service
  market-data-projection
  marketdata-publisher
  persistence
  query-service
  quickfix-gateway
  risk-service
)

mapfile -t actual_services < <(flyway_known_services)
if [[ "${actual_services[*]}" != "${expected_services[*]}" ]]; then
  echo "Flyway service inventory does not match the expected owner set." >&2
  exit 1
fi

for service in "${expected_services[@]}"; do
  flyway_service_exists "$service"
done

query_plan_checker="$repo_root/scripts/check-flyway-query-plans.sh"
for service in "${expected_services[@]}"; do
  if ! grep -Fq "  ${service})" "$query_plan_checker"; then
    echo "Flyway query-plan checker has no branch for $service." >&2
    exit 1
  fi
done

if flyway_service_exists unknown-service; then
  echo "Unknown Flyway service was accepted." >&2
  exit 1
fi

assert_mapping() {
  local service="$1"
  local function_name="$2"
  local expected="$3"
  local actual
  actual="$($function_name "$service")"
  if [[ "$actual" != "$expected" ]]; then
    echo "$function_name($service) returned '$actual', expected '$expected'." >&2
    exit 1
  fi
}

while IFS='|' read -r service project task env schema migration smoke_tables; do
  assert_mapping "$service" flyway_service_project_path "$project"
  assert_mapping "$service" flyway_service_task_prefix "$task"
  assert_mapping "$service" flyway_service_env_prefix "$env"
  assert_mapping "$service" flyway_service_schema "$schema"
  assert_mapping "$service" flyway_service_migration_dir "$migration"
  assert_mapping "$service" flyway_service_smoke_tables "$(printf '%b' "$smoke_tables")"
done <<'MAPPINGS'
account-service|:services:account-service|accountService|ACCOUNT_SERVICE|account_service|services/account-service/src/main/resources/db/migration/account-service|account_limits\naccount_positions\naccount_reservations
market-data-projection|:services:market-data-projection|marketDataProjection|MARKET_DATA_PROJECTION|market_data_projection|services/market-data-projection/src/main/resources/db/migration/market-data-projection|matching_event_inbox\ninstrument_market_data\nmarket_data_events_outbox
marketdata-publisher|:services:marketdata-publisher|marketdataPublisher|MARKETDATA_PUBLISHER|marketdata_publisher|services/marketdata-publisher/src/main/resources/db/migration/marketdata-publisher|market_snapshots\noutbox\nrouting_policies\nrouting_policy_assignments
persistence|:services:persistence|persistence|PERSISTENCE|persistence|services/persistence/src/main/resources/db/migration/persistence|orders\nexecutions\ninbox
query-service|:services:query-service|queryService|QUERY_SERVICE|query_service|services/query-service/src/main/resources/db/migration/query-service|order_read_model\nexecution_read_model\naccount_summary_read_model\nactive_market_reference
quickfix-gateway|:services:quickfix-gateway|quickFixGateway|QUICKFIX_GATEWAY|quickfix_gateway|services/quickfix-gateway/src/main/resources/db/migration/quickfix-gateway|matching_event_inbox\nfix_delivery_intents\ngateway_operation_audit
risk-service|:services:risk-service|riskService|RISK_SERVICE|risk_service|services/risk-service/src/main/resources/db/migration/risk-service|outbox\nrisk_submissions
MAPPINGS

echo "Flyway service mapping functions are valid."

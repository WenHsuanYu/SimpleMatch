#!/usr/bin/env bash

flyway_known_services() {
  printf '%s\n' \
    'account-service' \
    'market-data-projection' \
    'marketdata-publisher' \
    'persistence' \
    'quickfix-gateway' \
    'risk-service'
}

flyway_service_exists() {
  case "$1" in
    account-service|market-data-projection|marketdata-publisher|persistence|quickfix-gateway|risk-service)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

flyway_service_project_path() {
  case "$1" in
    account-service)
      printf '%s\n' ':services:account-service'
      ;;
    market-data-projection)
      printf '%s\n' ':services:market-data-projection'
      ;;
    marketdata-publisher)
      printf '%s\n' ':services:marketdata-publisher'
      ;;
    persistence)
      printf '%s\n' ':services:persistence'
      ;;
    quickfix-gateway)
      printf '%s\n' ':services:quickfix-gateway'
      ;;
    risk-service)
      printf '%s\n' ':services:risk-service'
      ;;
    *)
      return 1
      ;;
  esac
}

flyway_service_task_prefix() {
  case "$1" in
    account-service)
      printf '%s\n' 'accountService'
      ;;
    market-data-projection)
      printf '%s\n' 'marketDataProjection'
      ;;
    marketdata-publisher)
      printf '%s\n' 'marketdataPublisher'
      ;;
    persistence)
      printf '%s\n' 'persistence'
      ;;
    quickfix-gateway)
      printf '%s\n' 'quickFixGateway'
      ;;
    risk-service)
      printf '%s\n' 'riskService'
      ;;
    *)
      return 1
      ;;
  esac
}

flyway_service_env_prefix() {
  case "$1" in
    account-service)
      printf '%s\n' 'ACCOUNT_SERVICE'
      ;;
    market-data-projection)
      printf '%s\n' 'MARKET_DATA_PROJECTION'
      ;;
    marketdata-publisher)
      printf '%s\n' 'MARKETDATA_PUBLISHER'
      ;;
    persistence)
      printf '%s\n' 'PERSISTENCE'
      ;;
    quickfix-gateway)
      printf '%s\n' 'QUICKFIX_GATEWAY'
      ;;
    risk-service)
      printf '%s\n' 'RISK_SERVICE'
      ;;
    *)
      return 1
      ;;
  esac
}

flyway_service_schema() {
  case "$1" in
    account-service)
      printf '%s\n' 'account_service'
      ;;
    market-data-projection)
      printf '%s\n' 'market_data_projection'
      ;;
    marketdata-publisher)
      printf '%s\n' 'marketdata_publisher'
      ;;
    persistence)
      printf '%s\n' 'persistence'
      ;;
    quickfix-gateway)
      printf '%s\n' 'quickfix_gateway'
      ;;
    risk-service)
      printf '%s\n' 'risk_service'
      ;;
    *)
      return 1
      ;;
  esac
}

flyway_service_migration_dir() {
  case "$1" in
    account-service|market-data-projection|marketdata-publisher|persistence|quickfix-gateway|risk-service)
      printf '%s\n' "services/$1/src/main/resources/db/migration/$1"
      ;;
    *)
      return 1
      ;;
  esac
}

flyway_service_smoke_tables() {
  case "$1" in
    account-service)
      printf '%s\n' account_limits account_positions account_reservations
      ;;
    market-data-projection)
      printf '%s\n' matching_event_inbox instrument_market_data market_data_events_outbox
      ;;
    marketdata-publisher)
      printf '%s\n' market_snapshots outbox routing_policies routing_policy_assignments
      ;;
    persistence)
      printf '%s\n' orders executions inbox
      ;;
    quickfix-gateway)
      printf '%s\n' matching_event_inbox fix_delivery_intents gateway_operation_audit
      ;;
    risk-service)
      printf '%s\n' outbox risk_submissions
      ;;
    *)
      return 1
      ;;
  esac
}

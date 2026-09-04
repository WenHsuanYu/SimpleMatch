#!/usr/bin/env bash

flyway_known_services() {
  printf '%s\n' \
    'account-service' \
    'market-data-projection' \
    'persistence' \
    'query-service' \
    'quickfix-gateway' \
    'risk-service'
}

flyway_service_exists() {
  case "$1" in
    account-service|market-data-projection|persistence|query-service|quickfix-gateway|risk-service)
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
    persistence)
      printf '%s\n' ':services:persistence'
      ;;
    query-service)
      printf '%s\n' ':services:query-service'
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
    persistence)
      printf '%s\n' 'persistence'
      ;;
    query-service)
      printf '%s\n' 'queryService'
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
    persistence)
      printf '%s\n' 'PERSISTENCE'
      ;;
    query-service)
      printf '%s\n' 'QUERY_SERVICE'
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
    persistence)
      printf '%s\n' 'persistence'
      ;;
    query-service)
      printf '%s\n' 'query_service'
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
    account-service|market-data-projection|persistence|query-service|quickfix-gateway|risk-service)
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
    persistence)
      printf '%s\n' orders executions inbox
      ;;
    query-service)
      printf '%s\n' order_read_model execution_read_model account_summary_read_model active_market_reference
      ;;
    quickfix-gateway)
      printf '%s\n' matching_event_inbox fix_delivery_intents gateway_operation_audit
      ;;
    risk-service)
      printf '%s\n' admission_journal outbox
      ;;
    *)
      return 1
      ;;
  esac
}

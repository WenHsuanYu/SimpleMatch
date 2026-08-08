#!/usr/bin/env bash

flyway_known_services() {
  printf '%s\n' \
    'account-service' \
    'marketdata-publisher' \
    'persistence' \
    'risk-service'
}

flyway_service_exists() {
  case "$1" in
    account-service|marketdata-publisher|persistence|risk-service)
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
    marketdata-publisher)
      printf '%s\n' ':services:marketdata-publisher'
      ;;
    persistence)
      printf '%s\n' ':services:persistence'
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
    marketdata-publisher)
      printf '%s\n' 'marketdataPublisher'
      ;;
    persistence)
      printf '%s\n' 'persistence'
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
    marketdata-publisher)
      printf '%s\n' 'MARKETDATA_PUBLISHER'
      ;;
    persistence)
      printf '%s\n' 'PERSISTENCE'
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
    marketdata-publisher)
      printf '%s\n' 'marketdata_publisher'
      ;;
    persistence)
      printf '%s\n' 'persistence'
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
    account-service|marketdata-publisher|persistence|risk-service)
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
    marketdata-publisher)
      printf '%s\n' market_snapshots outbox routing_policies routing_policy_assignments
      ;;
    persistence)
      printf '%s\n' orders executions inbox
      ;;
    risk-service)
      printf '%s\n' outbox risk_submissions
      ;;
    *)
      return 1
      ;;
  esac
}

#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=lib/flyway-services.sh
source "$repo_root/scripts/lib/flyway-services.sh"

postgres_host="${SIMPLEMATCH_LIVE_POSTGRES_HOST:-}"
postgres_port="${SIMPLEMATCH_LIVE_POSTGRES_PORT:-5432}"
postgres_user="${SIMPLEMATCH_LIVE_POSTGRES_USER:-}"
postgres_password="${SIMPLEMATCH_LIVE_POSTGRES_PASSWORD:-}"
database_name="${SIMPLEMATCH_LIVE_POSTGRES_DATABASE:-}"
ssl_mode="${SIMPLEMATCH_LIVE_POSTGRES_SSLMODE:-verify-full}"
ssl_root_cert="${SIMPLEMATCH_LIVE_POSTGRES_SSLROOTCERT:-}"
custom_jdbc_url="${SIMPLEMATCH_LIVE_POSTGRES_JDBC_URL:-}"

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

usage() {
  printf '%s\n' \
    'Usage: verify-postgres-live-certification.sh' \
    '' \
    'Required environment:' \
    '  SIMPLEMATCH_LIVE_POSTGRES_HOST' \
    '  SIMPLEMATCH_LIVE_POSTGRES_USER' \
    '  SIMPLEMATCH_LIVE_POSTGRES_PASSWORD' \
    '  SIMPLEMATCH_LIVE_POSTGRES_DATABASE' \
    '' \
    'Optional environment:' \
    '  SIMPLEMATCH_LIVE_POSTGRES_PORT       default: 5432' \
    '  SIMPLEMATCH_LIVE_POSTGRES_SSLMODE    default: verify-full; insecure modes are rejected' \
    '  SIMPLEMATCH_LIVE_POSTGRES_SSLROOTCERT CA certificate path for psql' \
    '  SIMPLEMATCH_LIVE_POSTGRES_JDBC_URL   complete JDBC URL for Flyway validation'
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
[[ $# -eq 0 ]] || die "Unknown argument: $1"

[[ -n "$postgres_host" ]] || die 'SIMPLEMATCH_LIVE_POSTGRES_HOST is required'
[[ -n "$postgres_user" ]] || die 'SIMPLEMATCH_LIVE_POSTGRES_USER is required'
[[ -n "$postgres_password" ]] || die 'SIMPLEMATCH_LIVE_POSTGRES_PASSWORD is required'
[[ -n "$database_name" ]] || die 'SIMPLEMATCH_LIVE_POSTGRES_DATABASE is required'
[[ "$ssl_mode" == "verify-full" ]] || die \
  'PostgreSQL production certification requires SIMPLEMATCH_LIVE_POSTGRES_SSLMODE=verify-full'
command -v psql >/dev/null 2>&1 || die 'psql is required for PostgreSQL live certification'
[[ -x "$repo_root/gradlew" ]] || die 'Gradle wrapper is missing'

export PGSSLMODE="$ssl_mode"
if [[ -n "$ssl_root_cert" ]]; then
  [[ -f "$ssl_root_cert" ]] || die "PostgreSQL SSL root certificate does not exist: $ssl_root_cert"
  export PGSSLROOTCERT="$ssl_root_cert"
fi

psql_exec() {
  local sql_statement="$1"
  PGPASSWORD="$postgres_password" psql \
    --host "$postgres_host" \
    --port "$postgres_port" \
    --username "$postgres_user" \
    --dbname "$database_name" \
    --no-password \
    --no-align \
    --tuples-only \
    --quiet \
    --set ON_ERROR_STOP=1 \
    --command "$sql_statement"
}

assert_equals() {
  local description="$1"
  local expected="$2"
  local actual="$3"
  [[ "$actual" == "$expected" ]] || die "$description: expected $expected, got ${actual:-missing}"
}

assert_relation_exists() {
  local schema_name="$1"
  local table_name="$2"
  local exists
  exists="$(psql_exec "SELECT to_regclass('${schema_name}.${table_name}') IS NOT NULL;")"
  assert_equals "${schema_name}.${table_name}" t "$exists"
}

assert_service_schema() {
  local service_name="$1"
  local schema_name
  local history_count
  schema_name="$(flyway_service_schema "$service_name")"

  assert_equals "$schema_name schema" t \
    "$(psql_exec "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = '${schema_name}');")"
  assert_relation_exists "$schema_name" flyway_schema_history
  history_count="$(psql_exec "SELECT COUNT(*) FROM ${schema_name}.flyway_schema_history WHERE success;")"
  [[ "$history_count" =~ ^[0-9]+$ && "$history_count" -gt 0 ]] || die \
    "$schema_name.flyway_schema_history has no successful migration"

  while IFS= read -r table_name; do
    [[ -z "$table_name" ]] && continue
    assert_relation_exists "$schema_name" "$table_name"
  done < <(flyway_service_smoke_tables "$service_name")
}

set_service_flyway_environment() {
  local service_name="$1"
  local env_prefix
  local jdbc_var
  local username_var
  local password_var
  local schema_var
  local jdbc_url="$custom_jdbc_url"

  if [[ -z "$jdbc_url" ]]; then
    jdbc_url="jdbc:postgresql://${postgres_host}:${postgres_port}/${database_name}?sslmode=${ssl_mode}"
  fi
  env_prefix="$(flyway_service_env_prefix "$service_name")"
  jdbc_var="${env_prefix}_FLYWAY_JDBC_URL"
  username_var="${env_prefix}_FLYWAY_USERNAME"
  password_var="${env_prefix}_FLYWAY_PASSWORD"
  schema_var="${env_prefix}_FLYWAY_SCHEMA"
  printf -v "$jdbc_var" '%s' "$jdbc_url"
  printf -v "$username_var" '%s' "$postgres_user"
  printf -v "$password_var" '%s' "$postgres_password"
  printf -v "$schema_var" '%s' "$(flyway_service_schema "$service_name")"
  export "$jdbc_var" "$username_var" "$password_var" "$schema_var"
}

cd "$repo_root"

assert_equals 'PostgreSQL primary status' f "$(psql_exec 'SELECT pg_is_in_recovery();')"
assert_equals 'PostgreSQL WAL level' logical "$(psql_exec 'SHOW wal_level;')"
assert_equals 'PostgreSQL TLS connection' t \
  "$(psql_exec 'SELECT COALESCE((SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), false);')"
assert_equals 'public Flyway history absence' f \
  "$(psql_exec "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL;")"

while IFS= read -r service_name; do
  [[ -z "$service_name" ]] && continue
  echo "Checking PostgreSQL schema and Flyway history for $service_name..."
  assert_service_schema "$service_name"
  set_service_flyway_environment "$service_name"
  task_prefix="$(flyway_service_task_prefix "$service_name")"
  echo "Running read-only Flyway info and validate for $service_name..."
  ./gradlew --no-daemon --stacktrace \
    "${task_prefix}FlywayInfo" \
    "${task_prefix}FlywayValidate"
  echo "Checking named PostgreSQL query plans for $service_name..."
  CI_POSTGRES_HOST="$postgres_host" \
    CI_POSTGRES_PORT="$postgres_port" \
    CI_POSTGRES_USER="$postgres_user" \
    CI_POSTGRES_PASSWORD="$postgres_password" \
    bash "$repo_root/scripts/check-flyway-query-plans.sh" \
      "$database_name" "$service_name"
done < <(flyway_known_services)

printf '%s\n' \
  'PostgreSQL live certification passed: primary/TLS/logical-WAL checks, all service-local schemas,' \
  'successful Flyway histories, read-only Flyway validation, and named query-plan checks succeeded.'

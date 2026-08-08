#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
source "$repo_root/scripts/lib/flyway-services.sh"

postgres_host="${CI_POSTGRES_HOST:-127.0.0.1}"
postgres_port="${CI_POSTGRES_PORT:-5432}"
postgres_user="${CI_POSTGRES_USER:-simplematch}"
postgres_password="${CI_POSTGRES_PASSWORD:-simplematch}"
postgres_admin_db="${CI_POSTGRES_ADMIN_DB:-postgres}"

psql_exec() {
  local database_name="$1"
  local sql_statement="$2"

  PGPASSWORD="$postgres_password" psql \
    --host "$postgres_host" \
    --port "$postgres_port" \
    --username "$postgres_user" \
    --dbname "$database_name" \
    --no-align \
    --tuples-only \
    --quiet \
    --set ON_ERROR_STOP=1 \
    --command "$sql_statement"
}

reset_service_database() {
  local database_name="$1"

  psql_exec "$postgres_admin_db" "DROP DATABASE IF EXISTS \"$database_name\";"
  psql_exec "$postgres_admin_db" "CREATE DATABASE \"$database_name\";"
}

set_service_flyway_env() {
  local service_name="$1"
  local database_name="$2"
  local env_prefix
  local jdbc_var
  local user_var
  local password_var

  env_prefix="$(flyway_service_env_prefix "$service_name")"
  jdbc_var="${env_prefix}_FLYWAY_JDBC_URL"
  user_var="${env_prefix}_FLYWAY_USERNAME"
  password_var="${env_prefix}_FLYWAY_PASSWORD"

  printf -v "$jdbc_var" 'jdbc:postgresql://%s:%s/%s' "$postgres_host" "$postgres_port" "$database_name"
  printf -v "$user_var" '%s' "$postgres_user"
  printf -v "$password_var" '%s' "$postgres_password"

  export "${jdbc_var?}" "${user_var?}" "${password_var?}"
}

assert_service_tables() {
  local service_name="$1"
  local database_name="$2"
  local schema_name
  local table_name
  local table_exists
  local history_count

  schema_name="$(flyway_service_schema "$service_name")"

  while IFS= read -r table_name; do
    [[ -z "$table_name" ]] && continue
    table_exists="$(psql_exec "$database_name" "SELECT to_regclass('${schema_name}.${table_name}') IS NOT NULL;")"

    if [[ "$table_exists" != "t" ]]; then
      echo "Expected table ${schema_name}.${table_name} was not created for $service_name in database $database_name." >&2
      return 1
    fi
  done < <(flyway_service_smoke_tables "$service_name")

  history_count="$(psql_exec "$database_name" "SELECT COUNT(*) FROM ${schema_name}.flyway_schema_history WHERE success;")"
  if [[ "$history_count" -lt 1 ]]; then
    echo "Flyway schema history in ${schema_name}.flyway_schema_history did not record any successful migration for $service_name." >&2
    return 1
  fi
}

cd "$repo_root"

while IFS= read -r service_name; do
  [[ -z "$service_name" ]] && continue

  task_prefix="$(flyway_service_task_prefix "$service_name")"
  database_name="simplematch_${service_name//-/_}_ci"

  echo "Preparing PostgreSQL database $database_name for $service_name..."
  reset_service_database "$database_name"
  set_service_flyway_env "$service_name" "$database_name"

  echo "Running Flyway info and migrate twice for $service_name..."
  ./gradlew -q --no-daemon --stacktrace \
    "${task_prefix}FlywayInfo" \
    "${task_prefix}FlywayMigrate"

  ./gradlew -q --no-daemon --stacktrace \
    "${task_prefix}FlywayMigrate"

  echo "Running PostgreSQL smoke assertions for $service_name..."
  assert_service_tables "$service_name" "$database_name"
  bash "$repo_root/scripts/check-flyway-query-plans.sh" "$database_name" "$service_name"
done < <(flyway_known_services)

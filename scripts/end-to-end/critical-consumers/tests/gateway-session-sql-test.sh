#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
result_dir="$(mktemp -d /tmp/simplematch-gateway-session-sql.XXXXXX)"
trap 'rm -rf -- "$result_dir"' EXIT

# Run only in an isolated migrated test database: the fixture rolls back.
PGPASSWORD="${CI_POSTGRES_PASSWORD:-simplematch}" psql \
  --host "${CI_POSTGRES_HOST:-127.0.0.1}" \
  --port "${CI_POSTGRES_PORT:-5432}" \
  --username "${CI_POSTGRES_USER:-simplematch}" \
  --dbname "${CI_POSTGRES_DATABASE:-simplematch_ci}" \
  --no-psqlrc --no-password --no-align --tuples-only --quiet \
  --set ON_ERROR_STOP=1 \
  --file "$script_dir/fixtures/gateway-session-padding.sql" \
  --file "$script_dir/../sql/gateway-session-state.sql" \
  --file "$script_dir/fixtures/gateway-session-rollback.sql" \
  >"$result_dir/session.json"
jq '.identity' "$result_dir/session.json" >"$result_dir/identity.json"
diff -u "$script_dir/baselines/gateway-session-identity.json" "$result_dir/identity.json"
printf '%s\n' 'Gateway SQL exports canonical CHAR identity and preserves VARCHAR identity.'

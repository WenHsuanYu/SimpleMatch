#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  printf '%s\n' 'Usage: verify-postgres-live-certification.sh' \
    'Requires SIMPLEMATCH_LIVE_POSTGRES_HOST, USER, PASSWORD, DATABASE (each with that prefix).' \
    'Optional PORT, SSLMODE=verify-full, SSLROOTCERT, JDBC_URL use the same prefix.' \
    'Runs read-only primary, TLS, logical-WAL, Flyway, schema and query-plan tests.'
  exit 0
fi
[[ $# -eq 0 ]] || { printf 'Unknown argument: %s\n' "$1" >&2; exit 2; }
exec "$repo_root/gradlew" -q :tools:dependency-tests:postgresLiveTest

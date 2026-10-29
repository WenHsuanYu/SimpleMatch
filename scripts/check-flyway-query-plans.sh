#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export SIMPLEMATCH_PLAN_DATABASE="${1:?database name is required}"
export SIMPLEMATCH_PLAN_SERVICE="${2:?service name is required}"
[[ $# -eq 2 ]] || { printf '%s\n' 'Expected database and service only' >&2; exit 2; }
exec "$repo_root/gradlew" -q :tools:dependency-tests:queryPlanTest

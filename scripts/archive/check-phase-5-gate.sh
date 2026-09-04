#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
offline_guide="$repo_root/config/market-reference/README.md"
trading_modules=(
  "$repo_root/services/risk-service/src/main/java"
  "$repo_root/services/quickfix-gateway/src/main/java"
  "$repo_root/services/account-service/src/main/java"
  "$repo_root/services/persistence/src/main/java"
)

if [[ ! -f "$offline_guide" ]]; then
  echo "Missing offline Market Reference guide: $offline_guide" >&2
  exit 1
fi

if ! rg -Fq 'market-reference-builder' "$offline_guide"; then
  echo "Offline Market Reference guide does not name the builder" >&2
  exit 1
fi

if rg -n --glob '*.java' \
    -e 'https?://' \
    -e 'HttpClient' \
    -e 'WebClient' \
    -e 'RestTemplate' \
    -e 'URLConnection' \
    "${trading_modules[@]}"; then
  echo "Trading modules must not synchronously call an exchange website." >&2
  exit 1
fi

echo 'Phase 5 gate structure check passed.'

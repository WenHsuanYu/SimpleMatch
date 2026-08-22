#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
fixture="$repo_root/services/marketdata-publisher/src/test/resources/fixtures/xtai-and-roco-snapshot.json"
trading_modules=(
  "$repo_root/services/marketdata-publisher/src/main/java"
  "$repo_root/services/risk-service/src/main/java"
  "$repo_root/services/quickfix-gateway/src/main/java"
  "$repo_root/services/account-service/src/main/java"
  "$repo_root/services/persistence/src/main/java"
)

if [[ ! -f "$fixture" ]]; then
  echo "Missing Phase 5 XTAI and ROCO fixture: $fixture" >&2
  exit 1
fi

for venue in XTAI ROCO; do
  if ! rg -Fq "\"venueMic\": \"$venue\"" "$fixture"; then
    echo "Phase 5 fixture does not include $venue" >&2
    exit 1
  fi
done

if ! rg -Fq '"securityType": "ETF"' "$fixture"; then
  echo "Phase 5 fixture does not exercise an unsupported instrument" >&2
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

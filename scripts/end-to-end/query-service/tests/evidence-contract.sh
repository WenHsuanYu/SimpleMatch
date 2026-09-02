#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
evidence_module="$script_dir/../lib/evidence.sh"

# The literals intentionally verify runtime expansion remains in the module.
grep -Fq 'FROM query_service.execution_read_model execution' "$evidence_module"
grep -Fq 'JOIN query_service.account_summary_read_model account' "$evidence_module"
grep -Fq 'JOIN query_service.active_market_reference reference' "$evidence_module"
grep -Fq "/api/v1/orders/\$order_id/executions" "$evidence_module"
grep -Fq "/api/v1/accounts/\$account_id/summary" "$evidence_module"
grep -Fq "/api/v1/market-reference/\$trading_day/\$venue_mic/\$symbol" "$evidence_module"
grep -Fq '/api/v1/freshness' "$evidence_module"
grep -Fq '.executions.data | length > 0' "$evidence_module"
grep -Fq -- '--reset-offsets --to-earliest --execute' "$evidence_module"
grep -Fq -- "--group \"\$group\" --describe" "$evidence_module"
grep -Fq 'seen > 0 && failed == 0' "$evidence_module"
grep -Fq "query:v1:market-reference:\$trading_day:\$venue_mic:\$symbol" "$evidence_module"

printf 'Query-service evidence collector contract is valid.\n'

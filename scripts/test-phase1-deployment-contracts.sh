#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

bash "$script_dir/verify-outbox-connector-contracts.sh"
bash "$script_dir/test-local-resilience.sh"

printf '%s\n' 'Phase 1 connector, resilience orchestration, and sensitive-log contracts are valid.'

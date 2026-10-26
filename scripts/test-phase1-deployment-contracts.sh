#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

bash "$script_dir/test-cdc-observer-fixture-contract.sh"
bash "$script_dir/test-local-certification-focused-diagnostic.sh"
bash "$script_dir/verify-outbox-connector-contracts.sh"
bash "$script_dir/test-local-resilience.sh"

python3 "$script_dir/end-to-end/query-service/tests/certification-fixtures-contract.py"
python3 "$script_dir/end-to-end/query-service/tests/certification-inputs-contract.py"
python3 "$script_dir/end-to-end/query-service/tests/read-matrix-contract.py"
python3 "$script_dir/end-to-end/query-service/tests/operator-boundary-contract.py"
bash "$script_dir/end-to-end/critical-consumers/tests/operator-credential-contract.sh"

printf '%s\n' 'Phase 1 connector, resilience orchestration, and sensitive-log contracts are valid.'

#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

bash "$script_dir/test-cdc-observer-fixture-contract.sh"
bash "$script_dir/test-local-certification-focused-diagnostic.sh"
"$script_dir/../gradlew" -p "$script_dir/.." :tools:dependency-tests:test
bash "$script_dir/validate-local-resilience-contract.sh"
bash "$script_dir/test-local-production-like.sh"
bash "$script_dir/validate-matching-producer-contract.sh"
bash "$script_dir/test-local-docker-storage-preflight.sh"
bash "$script_dir/test-local-certification-job-supervision.sh"
bash "$script_dir/test-local-resource-budget-evidence.sh"
bash "$script_dir/test-local-resilience-dependencies.sh"
bash "$script_dir/test-connect-rest-tunnel.sh"
bash "$script_dir/test-local-connect-worker-loss.sh"
bash "$script_dir/test-local-kind-image-cache-preflight.sh"
"$script_dir/../gradlew" -q -p "$script_dir/.." :tools:recovery-evidence-verifier:test
bash "$script_dir/end-to-end/market-data/tests/streamer-recovery-contract.sh"
bash "$script_dir/test-local-resilience.sh"

python3 "$script_dir/end-to-end/query-service/tests/certification-fixtures-contract.py"
python3 "$script_dir/end-to-end/query-service/tests/certification-inputs-contract.py"
python3 "$script_dir/end-to-end/query-service/tests/read-matrix-contract.py"
python3 "$script_dir/end-to-end/query-service/tests/operator-boundary-contract.py"
bash "$script_dir/end-to-end/critical-consumers/tests/operator-credential-contract.sh"

printf '%s\n' 'Phase 1 connector, resilience orchestration, and sensitive-log contracts are valid.'

#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
fixture_module="$script_dir/../lib/execution-fixture.sh"
interfaces_module="$script_dir/../../critical-consumers/lib/test-interfaces.sh"
cluster_module="$script_dir/../../critical-consumers/lib/cluster-data.sh"
prepared_test="$script_dir/../../../../services/quickfix-gateway/src/test/java/com/simplematch/quickfixgateway/fix/QuickFixPreparedSubmissionLiveCertificationTest.java"

for script in "$fixture_module" "$interfaces_module" "$cluster_module"; do
  bash -n "$script"
done

grep -Fq 'PUBLIC_FIX_CROSS' "$fixture_module"
grep -Fq 'open_gateway_from_fresh_observations' "$fixture_module"
grep -Fq 'start_fix_submit_client' "$fixture_module"
grep -Fq 'seed_account_limit' "$fixture_module"
grep -Fq 'seed_account_position' "$fixture_module"
grep -Fq 'RESERVATION_STATUS_APPLIED' "$fixture_module"
grep -Fq 'require_clean_baseline' "$fixture_module"
grep -Fq 'start_kafka_observation_adapter "$retained_evidence_dir"' "$fixture_module"
grep -Fq 'stop_kafka_observation_adapter' "$fixture_module"
grep -Fq 'delete_kafka_observer_pod' "$fixture_module"
grep -Fq 'cleanup_query_execution_fixture' "$fixture_module"
grep -Fq 'expected_active_matching_orders=0' "$fixture_module"
grep -Fq 'expected_active_matching_orders=1' "$fixture_module"
grep -Fq 'expected_active_matching_orders' "$fixture_module"
grep -Fq 'SIMPLEMATCH_LIVE_FIX_SIDE' "$interfaces_module"
grep -Fq 'SIMPLEMATCH_LIVE_FIX_SIDE' "$prepared_test"

submit_line="$(grep -n '^query_fixture_submit_order()' "$fixture_module" | cut -d: -f1)"
client_line="$(grep -n 'start_fix_submit_client || return 1' "$fixture_module" | head -n 1 | cut -d: -f1)"
open_line="$(grep -n 'open_gateway_from_fresh_observations' "$fixture_module" | tail -n 1 | cut -d: -f1)"
release_line="$(grep -n '^  release_fix_submit_client$' "$fixture_module" | head -n 1 | cut -d: -f1)"
[[ "$submit_line" =~ ^[0-9]+$ && "$client_line" =~ ^[0-9]+$ &&
  "$open_line" =~ ^[0-9]+$ && "$release_line" =~ ^[0-9]+$ ]] || {
  printf '%s\n' 'Execution fixture must expose client/open/release sequencing.' >&2
  exit 1
}
(( submit_line < client_line && client_line < open_line && open_line < release_line )) || {
  printf '%s\n' 'Fresh Gateway observations must precede each FIX release.' >&2
  exit 1
}

if grep -Fq 'run-risk-matching-command-e2e.sh' "$fixture_module"; then
  printf '%s\n' 'Execution fixture must use public FIX, not direct RM-1 helper commands.' >&2
  exit 1
fi

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-query-fixture-contract.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT

if ! (
  set -Eeuo pipefail
  evidence_dir="$temporary_directory/evidence"
  die() { return 1; }
  kns() {
    printf '%s\n' 'Forwarding from 127.0.0.1:43123 -> 5001'
    sleep 30
  }
  # shellcheck source=/dev/null
  source "$interfaces_module"
  if ! start_fix_port_forward; then
    exit 1
  fi
  [[ -d "$evidence_dir/fix" ]] || exit 1
  [[ -f "$evidence_dir/fix/port-forward.log" ]] || exit 1
  stop_fix_port_forward
); then
  printf '%s\n' 'FIX port-forward must create its evidence directory before logging.' >&2
  exit 1
fi

printf 'Query-service execution fixture contract is valid.\n'

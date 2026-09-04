#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
plan_file="$repo_root/docs/archive/taiwan-event-driven-refactor-plan.md"
policy_file="$repo_root/docs/cross-cutting-transaction-and-consistency-policy.md"

if [[ "${1:-}" == "--plan" ]]; then
  plan_file="${2:?missing plan path after --plan}"
  shift 2
fi

if [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--plan <path>]" >&2
  exit 2
fi

if [[ ! -f "$policy_file" ]]; then
  echo "Missing canonical transaction policy: $policy_file" >&2
  exit 1
fi

if [[ ! -f "$plan_file" ]]; then
  echo "Missing refactoring plan: $plan_file" >&2
  exit 1
fi

required_phases=(
  'Phase 5: Create the market-reference publisher capability'
  'Phase 6: Deepen account reservation authority'
  'Phase 7: Deepen durable risk admission'
  'Phase 11: Complete account lifecycle integration'
  'Phase 12: Build durable projections and Redis read models'
  'Phase 13: Create market-data projection and streaming capabilities'
)
required_fields=(
  'Applicable policy'
  'Transaction owner'
  'Atomic writes'
  'Work outside the transaction'
  'Work inside the transaction'
  'Failure outcome'
  'Retry and idempotency'
  'Concurrency control'
  'Timeout policy'
  'Verification'
)

phase_section() {
  local phase="$1"

  awk -v phase="### $phase" '
    $0 == phase { in_phase = 1; next }
    in_phase && /^### Phase / { exit }
    in_phase { print }
  ' "$plan_file"
}

status=0

for phase in "${required_phases[@]}"; do
  section="$(phase_section "$phase")"
  if [[ -z "$section" ]]; then
    echo "Missing required phase heading: $phase" >&2
    status=1
    continue
  fi

  if ! grep -Fqx '#### Transaction Acceptance Criteria' <<<"$section"; then
    echo "Missing Transaction Acceptance Criteria section: $phase" >&2
    status=1
    continue
  fi

  if ! grep -Fq 'cross-cutting-transaction-and-consistency-policy.md' <<<"$section"; then
    echo "Missing canonical policy reference: $phase" >&2
    status=1
  fi

  for field in "${required_fields[@]}"; do
    if ! grep -Fqx "##### $field" <<<"$section"; then
      echo "Missing transaction acceptance field in $phase: $field" >&2
      status=1
    fi
  done
done

if [[ $status -eq 0 ]]; then
  echo 'Transaction acceptance criteria structure check passed.'
fi

exit "$status"

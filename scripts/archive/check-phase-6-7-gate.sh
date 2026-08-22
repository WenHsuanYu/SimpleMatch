#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
plan="$repo_root/docs/taiwan-event-driven-refactor-plan.md"

phase_block="$(sed -n '/### Phase 6:/,/### Phase 8:/p' "$plan")"
if rg -n -- '- \[ \] Commit 6\.|- \[ \] Concurrent reserves|- \[ \] Duplicate reserve|- \[ \] Account state' <<<"$phase_block"; then
  echo 'Phase 6 gate is incomplete.' >&2
  exit 1
fi

phase_block="$(sed -n '/### Phase 7:/,/### Phase 8:/p' "$plan")"
if rg -n -- '- \[ \] Commit 7\.|- \[ \] No database transaction|- \[ \] Every pending|- \[ \] Equivalent retries|- \[ \] Conflicting retries' <<<"$phase_block"; then
  echo 'Phase 7 gate is incomplete.' >&2
  exit 1
fi

required_files=(
  services/account-service/src/main/java/com/simplematch/accountservice/reservation/AccountReservationApplicationService.java
  services/account-service/src/main/resources/db/migration/account-service/V2__add_account_authority_lifecycle_tables.sql
  services/risk-service/src/main/java/com/simplematch/riskservice/admission/OrderAdmissionApplicationService.java
  services/risk-service/src/main/resources/db/migration/risk-service/V2__add_durable_admission_journal.sql
  proto/risk_v2.proto
)
for required_file in "${required_files[@]}"; do
  [[ -f "$repo_root/$required_file" ]] || { echo "Missing gate artifact: $required_file" >&2; exit 1; }
done

echo 'Phase 6/7 gate structure check passed.'

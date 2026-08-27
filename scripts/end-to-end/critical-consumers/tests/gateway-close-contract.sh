#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
runner="$script_dir/../run-gateway-close-certification.sh"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-gateway-close-contract.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT

fail() {
  printf 'Gateway close contract: %s\n' "$*" >&2
  exit 1
}

bash -n "$runner"

# shellcheck source=scripts/end-to-end/critical-consumers/run-gateway-close-certification.sh
source "$runner"
declare -F die >/dev/null ||
  fail 'close runner must provide the fail-closed callback required by shared helpers'

before="$temporary_directory/commands-before.json"
after="$temporary_directory/commands-after.json"
committed="$temporary_directory/matching-committed.json"
events="$temporary_directory/events.json"
consumers="$temporary_directory/consumers.json"

jq -n '{topic:"matching.commands", partitions:[range(0;15) as $p | {partition:$p,offset:(10+$p)}]}' \
  >"$before"
jq -n '{topic:"matching.commands", partitions:[range(0;15) as $p | {partition:$p,offset:(11+$p)}]}' \
  >"$after"
close_barriers_advanced_exactly_once "$before" "$after" ||
  fail 'one Close Barrier per matching.commands partition must pass'

jq '.partitions[7].offset += 1' "$after" >"$temporary_directory/commands-too-far.json"
if close_barriers_advanced_exactly_once "$before" "$temporary_directory/commands-too-far.json"; then
  fail 'a partition advancing by more than one must fail the Close Barrier proof'
fi

jq -n '{
  topic:"matching.commands",
  partitions:[range(0;15) as $p | {partition:$p,committedOffset:(11+$p)}]
}' >"$committed"
matching_committed_covers_commands "$after" "$committed" ||
  fail 'Matching committed positions at command log end must pass'

jq '.partitions[4].committedOffset -= 1' "$committed" \
  >"$temporary_directory/matching-behind.json"
if matching_committed_covers_commands "$after" "$temporary_directory/matching-behind.json"; then
  fail 'a Matching owner behind its Close Barrier must fail the proof'
fi

jq -n '{
  topic:"matching.events",
  partitions:[range(0;15) as $p | {partition:$p,offset:(if $p == 3 then 5 else 0 end)}]
}' >"$events"
jq -n '{
  persistenceProgress:[{partition_id:3,last_processed_offset:4}],
  accountProgress:[{partition_id:3,last_processed_offset:4}],
  quickfixProgress:[{partition_id:3,last_processed_offset:4}],
  persistenceQuarantines:0,
  accountQuarantines:0,
  quickfixQuarantines:0,
  quickfixPendingIntents:1,
  activeMatchingOrders:0
}' >"$consumers"
critical_consumers_cover_events "$events" "$consumers" ||
  fail 'all critical consumers caught up through the event log end must pass'

jq '.quickfixProgress[0].last_processed_offset = 3' "$consumers" \
  >"$temporary_directory/consumer-behind.json"
if critical_consumers_cover_events "$events" "$temporary_directory/consumer-behind.json"; then
  fail 'a critical consumer behind the close event must fail the proof'
fi

jq '.accountQuarantines = 1' "$consumers" >"$temporary_directory/consumer-quarantined.json"
if critical_consumers_cover_events "$events" "$temporary_directory/consumer-quarantined.json"; then
  fail 'a quarantined critical consumer must fail the close proof'
fi

grep -F 'source "$script_dir/lib/failure-support.sh"' "$runner" >/dev/null ||
  fail 'close certification must reuse the existing critical-consumer runtime'
grep -F 'source "$script_dir/lib/system-observation.sh"' "$runner" >/dev/null ||
  fail 'close certification must reuse the existing observation collector'
grep -F 'capture_gateway_observation' "$runner" >/dev/null ||
  fail 'close certification must use normalized Gateway observations'
grep -F 'gateway_request POST /operations/close-day' "$runner" >/dev/null ||
  fail 'close certification must cross the authenticated Gateway operations seam'
grep -F 'capture_matching_committed_offsets' "$runner" >/dev/null ||
  fail 'close certification must prove durable Matching command progress'
grep -F 'select(' "$runner" >/dev/null ||
  fail 'Matching CLOSED capture must preserve the runtime object after validation'
grep -F '.partition_state == "CLOSED"' "$runner" >/dev/null ||
  fail 'close certification must prove every Matching runtime reaches CLOSED'
grep -F 'wait_critical_consumers_to_close' "$runner" >/dev/null ||
  fail 'close certification must prove critical-consumer drain'
grep -F 'EXPIRED "$evidence_dir/close/expired-order.json"' "$runner" >/dev/null ||
  fail 'close certification must prove the resting order expires'

printf 'Gateway close certification contracts are valid.\n'

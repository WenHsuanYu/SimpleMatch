#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
evidence_module="$script_dir/../lib/evidence.sh"
cluster_data_module="$script_dir/../../critical-consumers/lib/cluster-data.sh"

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

# The literals intentionally verify runtime expansion remains in the module.
grep -Fq 'FROM query_service.execution_read_model execution' "$evidence_module"
grep -Fq 'JOIN query_service.account_summary_read_model account' "$evidence_module"
grep -Fq 'JOIN query_service.active_market_reference reference' "$evidence_module"
grep -Fq "/api/v1/orders/\$order_id/executions" "$evidence_module"
grep -Fq "/api/v1/accounts/\$account_id/summary" "$evidence_module"
grep -Fq "/api/v1/market-reference/\$trading_day/\$venue_mic/\$symbol" "$evidence_module"
grep -Fq '/api/v1/freshness' "$evidence_module"
grep -Fq '.executions.data | length > 0' "$evidence_module"
grep -Fq 'wait_for_query_fixture' "$evidence_module"
grep -Fq 'capture_query_replay_boundary' "$evidence_module"
grep -Fq 'capture_topic_offsets matching.events' "$evidence_module"
grep -Fq 'capture_topic_offsets account.lifecycle' "$evidence_module"
grep -Fq -- '--reset-offsets --from-file' "$evidence_module"
grep -Fq '"\($topic),\(.partition),\(.offset)"' "$evidence_module" || {
  printf '%s\n' 'Query replay reset must use Kafka topic,partition,offset CSV.' >&2
  exit 1
}
if grep -Fq -- '--reset-offsets --to-earliest' "$evidence_module"; then
  printf '%s\n' 'Query replay must not use an unbounded --to-earliest reset.' >&2
  exit 1
fi

source "$evidence_module"
jq -n '{topic:"matching.events",partitions:[range(0;15) |
  {partition:.,offset:(100 + .)}]}' >"$temporary_directory/boundary.json"
write_query_consumer_reset_file \
  "$temporary_directory/boundary.json" \
  "$temporary_directory/reset.csv"
[[ "$(sed -n '1p' "$temporary_directory/reset.csv")" == 'matching.events,0,100' ]] || {
  printf '%s\n' 'Query replay reset must render Kafka CSV rows.' >&2
  exit 1
}
[[ "$(wc -l <"$temporary_directory/reset.csv")" -eq 15 ]] || {
  printf '%s\n' 'Query replay reset must retain all 15 partitions.' >&2
  exit 1
}

grep -Fq -- "--group \"\$group\" --describe" "$evidence_module"
grep -Fq 'seen > 0 && failed == 0' "$evidence_module"
grep -Fq "query:v1:market-reference:\$trading_day:\$venue_mic:\$symbol" "$evidence_module"
grep -Fq "'admissionStateCounts'" "$cluster_data_module"
grep -Fq "'accountReservationStateCounts'" "$cluster_data_module"
grep -Fq "'marketDataProgress'" "$cluster_data_module"
grep -Fq 'SELECT partition_id, last_processed_offset, recovery_state' \
  "$cluster_data_module" || {
  printf '%s\n' \
    'Market-data progress evidence must include last_processed_offset.' >&2
  exit 1
}
grep -Fq "capture_query_service_outage_state" "$cluster_data_module"

printf 'Query-service evidence collector contract is valid.\n'

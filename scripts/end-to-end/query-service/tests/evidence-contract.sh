#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
evidence_module="$script_dir/../lib/evidence.sh"

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

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

# Observe the reset command and its uploaded input at the Kubernetes command interface.
(
  evidence_dir="$temporary_directory"
  calls="$temporary_directory/reset.calls"
  kafka_pod() { printf 'kafka-0\n'; }
  kns() {
    printf '%s\n' "$*" >>"$calls"
    if [[ "$*" == *' sh -c '* ]]; then
      cat >"$temporary_directory/uploaded.csv"
    fi
  }
  reset_query_consumer_group query-service-matching-events matching.events \
    "$temporary_directory/boundary.json" || exit 1
  cmp "$temporary_directory/reset.csv" "$temporary_directory/uploaded.csv" || exit 1
  grep -Fq -- '--reset-offsets --from-file /tmp/query-service-matching-events-offsets.txt --execute' \
    "$calls" || exit 1
  if grep -Fq -- '--to-earliest' "$calls"; then exit 1; fi
  : >"$calls"
  if reset_query_consumer_group query-service-matching-events matching.events \
      "$temporary_directory/missing.json"; then
    exit 1
  fi
  [[ ! -s "$calls" ]]
) || {
  printf '%s\n' 'Query replay must reset from the captured boundary and reject missing evidence.' >&2
  exit 1
}

printf 'Query-service evidence collector contract is valid.\n'

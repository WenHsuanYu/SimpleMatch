#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/matching-status.sh
source "$script_dir/../lib/matching-status.sh"

fail() {
  printf 'Matching status contract: %s\n' "$*" >&2
  exit 1
}

gateway_stale_millis="$(
  ruby -r yaml - "$repo_root/services/quickfix-gateway/src/main/resources/application.yaml" <<'RUBY'
path = ARGV.fetch(0)
config = YAML.safe_load(File.read(path, encoding: "UTF-8"), aliases: true)
value = config.dig("simplematch", "quickfix-gateway", "operations", "stale-status-after")
match = /\A([0-9]+)s\z/.match(value.to_s)
abort "Gateway stale-status-after must use whole seconds for this contract" unless match
puts match[1].to_i * 1000
RUBY
)"
[[ "$matching_runtime_default_max_age_millis" == "$gateway_stale_millis" ]] ||
  fail 'Matching freshness limit must equal the Gateway stale-status threshold'

fresh_runtime='{
  "schema_version":1,
  "updated_at_epoch_ms":10000,
  "runtime_state":"READY",
  "partition_state":"OPEN",
  "highest_contiguous_completed_offset":0,
  "next_commit_offset":null,
  "pending_inputs":0,
  "pending_publications":0
}'
matching_runtime_is_ready 14000 5000 <<<"$fresh_runtime" ||
  fail 'fresh READY/OPEN runtime must not require next_commit_offset'
if matching_runtime_is_ready 15001 5000 <<<"$fresh_runtime"; then
  fail 'stale Matching runtime must be rejected'
fi
if matching_runtime_is_ready 9000 5000 <<<"$fresh_runtime"; then
  fail 'future-dated Matching runtime must be rejected'
fi
if matching_runtime_is_ready 14000 5000 <<<"$(jq 'del(.updated_at_epoch_ms)' <<<"$fresh_runtime")"; then
  fail 'runtime without source timestamp must be rejected'
fi
if matching_runtime_is_ready 14000 5000 <<<"$(jq '.schema_version=2' <<<"$fresh_runtime")"; then
  fail 'unknown runtime schema must be rejected'
fi

runtime_file="$(mktemp)"
snapshot_file="$(mktemp)"
trap 'rm -f "$runtime_file" "$snapshot_file"' EXIT
printf '%s\n' "$fresh_runtime" >"$runtime_file"
[[ "$(matching_runtime_observed_at "$runtime_file")" == "1970-01-01T00:00:10.000Z" ]] ||
  fail 'Matching observedAt must come from updated_at_epoch_ms'

committed_snapshot="$(normalize_matching_committed_offsets <<'EOF_OFFSETS'
Consumer group 'matching-partition-consumer-0' has no active members.
GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
matching-partition-consumer-0 matching.commands 0 1 1 0 - - -
unrelated-consumer matching.commands 7 9 9 0 - - -
EOF_OFFSETS
)"
jq -e '.partitions == [{partition:0, committedOffset:1}]' <<<"$committed_snapshot" >/dev/null ||
  fail 'Kafka CURRENT-OFFSET must remain the durable Matching position'

if normalize_matching_committed_offsets >/dev/null 2>&1 <<'EOF_OFFSETS'
GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
matching-partition-consumer-0 matching.commands 1 1 1 0 - - -
EOF_OFFSETS
then
  fail 'group-to-partition mismatch must be rejected'
fi
if normalize_matching_committed_offsets >/dev/null 2>&1 <<'EOF_OFFSETS'
GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
matching-partition-consumer-0 matching.commands 0 - 1 - - - -
EOF_OFFSETS
then
  fail 'missing durable committed position must be rejected'
fi
if normalize_matching_committed_offsets >/dev/null 2>&1 <<'EOF_OFFSETS'
GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
matching-partition-consumer-0 matching.commands 0 1 1 0 - - -
matching-partition-consumer-0 matching.commands 0 1 1 0 - - -
EOF_OFFSETS
then
  fail 'duplicate committed position must be rejected'
fi

printf '%s\n' "$committed_snapshot" >"$snapshot_file"
[[ "$(matching_committed_offset_for_partition "$snapshot_file" 0)" == 1 ]] ||
  fail 'partition lookup must preserve committed offset 1'
if matching_committed_offset_for_partition "$snapshot_file" 1 >/dev/null 2>&1; then
  fail 'missing partition lookup must fail'
fi

printf '%s\n' 'Matching status semantic contracts are valid.'

#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/end-to-end/market-data/lib/verdict.sh
source "$script_dir/../lib/verdict.sh"

fixture_dir="$(mktemp -d)"
trap 'rm -rf -- "$fixture_dir"' EXIT

cat >"$fixture_dir/critical-before.json" <<'JSON'
{"persistenceProgress":[{"partition_id":0,"last_processed_offset":9}],"accountProgress":[],"quickfixProgress":[]}
JSON
cp "$fixture_dir/critical-before.json" "$fixture_dir/critical-after.json"
cat >"$fixture_dir/baseline-snapshot.json" <<'JSON'
{"sourceMatchingEventId":"event-1","venueMic":"XTAI","symbol":"1101","instrumentSequence":1,"sourcePartitionId":0,"sourceKafkaOffset":9,"completeSnapshot":true,"lastTrade":null,"bids":[{"priceUnits":100000,"quantityShares":100}],"asks":[]}
JSON
cp "$fixture_dir/baseline-snapshot.json" "$fixture_dir/rebuilt-snapshot.json"
cat >"$fixture_dir/restoration.json" <<'JSON'
{"redisRepaired":true,"projectionReady":true,"streamerReady":true,"matchingReady":15,"criticalConsumersReady":true}
JSON

evaluate_market_data_verdict "$fixture_dir" "$fixture_dir/verdict.json"
jq -e '.status == "PASS" and (.checks | all(.passed == true))' \
  "$fixture_dir/verdict.json" >/dev/null

jq '.sourceKafkaOffset = 10' "$fixture_dir/rebuilt-snapshot.json" \
  >"$fixture_dir/rebuilt-snapshot.tmp"
mv "$fixture_dir/rebuilt-snapshot.tmp" "$fixture_dir/rebuilt-snapshot.json"
if evaluate_market_data_verdict "$fixture_dir" "$fixture_dir/failure.json"; then
  printf 'market-data verdict accepted a non-deterministic rebuild\n' >&2
  exit 1
fi
jq -e '.status == "FAIL" and any(.checks[]; .name == "deterministicRebuild" and .passed == false)' \
  "$fixture_dir/failure.json" >/dev/null

printf 'Market-data certification verdict contract is valid.\n'

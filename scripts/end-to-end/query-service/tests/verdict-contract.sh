#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/end-to-end/query-service/lib/verdict.sh
source "$script_dir/../lib/verdict.sh"

fixture_dir="$(mktemp -d)"
trap 'rm -rf -- "$fixture_dir"' EXIT

cat >"$fixture_dir/critical-before.json" <<'JSON'
{"persistenceProgress":[{"partition_id":0,"last_processed_offset":9}],"accountProgress":[],"quickfixProgress":[]}
JSON
cp "$fixture_dir/critical-before.json" "$fixture_dir/critical-after.json"

cat >"$fixture_dir/baseline.json" <<'JSON'
{"order":{"data":{"orderId":"order-1","state":"FILLED"}},"executions":{"data":[{"executionId":"execution-1","orderId":"order-1"}]},"accountSummary":{"data":{"accountId":"account-1","lifecycleState":"ACTIVE"}},"marketReference":{"data":{"tradingDay":"2026-08-31","artifactId":"artifact-1","venueMic":"XTAI","symbol":"1101"}},"freshness":{"partitions":[{"sourceTopic":"account.lifecycle","partition":0,"lastProcessedOffset":4,"recoveryState":"READY"},{"sourceTopic":"matching.events","partition":0,"lastProcessedOffset":9,"recoveryState":"READY"}]}}
JSON
cp "$fixture_dir/baseline.json" "$fixture_dir/redis-outage.json"
cp "$fixture_dir/baseline.json" "$fixture_dir/rebuilt.json"
cat >"$fixture_dir/restoration.json" <<'JSON'
{"redisKeysPresent":true,"queryServiceReady":true,"matchingReady":15,"criticalConsumersReady":true}
JSON

evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/verdict.json"
jq -e '.status == "PASS" and (.checks | all(.passed == true))' \
  "$fixture_dir/verdict.json" >/dev/null

jq '.executions.data[0].executionId = "execution-2"' "$fixture_dir/rebuilt.json" \
  >"$fixture_dir/rebuilt.tmp"
mv "$fixture_dir/rebuilt.tmp" "$fixture_dir/rebuilt.json"
if evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/failure.json"; then
  printf 'query-service verdict accepted a non-deterministic rebuild\n' >&2
  exit 1
fi
jq -e '.status == "FAIL" and any(.checks[];
  .name == "deterministicRebuild" and .passed == false)' \
  "$fixture_dir/failure.json" >/dev/null

cp "$fixture_dir/baseline.json" "$fixture_dir/rebuilt.json"
jq '.executions.data = []' "$fixture_dir/baseline.json" >"$fixture_dir/baseline.tmp"
mv "$fixture_dir/baseline.tmp" "$fixture_dir/baseline.json"
if evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/empty-execution.json"; then
  printf 'query-service verdict accepted an empty execution fixture\n' >&2
  exit 1
fi

printf 'Query-service certification verdict contract is valid.\n'

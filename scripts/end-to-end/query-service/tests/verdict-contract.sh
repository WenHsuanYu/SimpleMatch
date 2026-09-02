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
cp "$fixture_dir/critical-before.json" "$fixture_dir/critical-during-query-outage.json"
cat >"$fixture_dir/query-outage.json" <<'JSON'
{"queryPodCount":0,"queryPodNames":[]}
JSON
jq -n --slurpfile state "$fixture_dir/critical-before.json" '
  def offsets($base): [range(0; 15) |
    {partition:., committedOffset:($base + .)}];
  def pathHealth: [
    {path:"admission",resource:"deployment/risk-service"},
    {path:"reservation",resource:"deployment/account-service"},
    {path:"matching",resource:"statefulset/matching"},
    {path:"persistence",resource:"deployment/persistence"},
    {path:"account",resource:"deployment/account-service"},
    {path:"quickfix",resource:"statefulset/quickfix-gateway"},
    {path:"marketData",resource:"deployment/market-data-projection"}
  ] | map(. as $entry | $entry + {
    desiredReplicas:(if $entry.path == "matching" then 15 else 1 end),
    readyReplicas:(if $entry.path == "matching" then 15 else 1 end),
    podCount:(if $entry.path == "matching" then 15 else 1 end),
    readyPodCount:(if $entry.path == "matching" then 15 else 1 end),
    restartCount:0,
    pods:(if $entry.path == "matching" then
      [range(0; 15) |
        {name:($entry.path + "-" + tostring),uid:($entry.path + "-uid-" + tostring),
         phase:"Running",ready:true,restartCount:0}]
    else
      [{name:($entry.path + "-0"),uid:($entry.path + "-uid"),phase:"Running",ready:true,restartCount:0}]
    end)
  });
  def matchingTopology: {
    statefulset:{name:"matching",uid:"matching-statefulset-uid",desiredReplicas:15,
      readyReplicas:15,currentRevision:"matching-revision",updateRevision:"matching-revision"},
    expectedOrdinals:[range(0;15) | tostring],
    expectedPodNames:[range(0;15) | ("matching-" + tostring)],
    pods:[range(0;15) |
      {name:("matching-" + tostring),uid:("matching-uid-" + tostring),ordinal:tostring,
       node:("worker-" + tostring),ready:true,ownerStatefulSet:true,
       controllerRevisionHash:"matching-revision",
       pvc:("matching-baseline-matching-" + tostring),pvcPhase:"Bound",
       pvcAccessModes:["ReadWriteOncePod"],pv:("pv-" + tostring),
       pvNodeAffinityNodes:[("worker-" + tostring)]}],
    unownedMatchingPods:[],unexpectedMatchingPods:[]
  };
  {
    probeDurationSeconds:2,
    probeStartedEpochMs:1000,
    probeCompletedEpochMs:1800,
    elapsedMilliseconds:800,
    commandTimeoutSeconds:1,
    sampleCount:2,
    samples:[range(0; 2) as $sample |
      {sampleIndex:$sample, queryPodCount:0, matchingReady:15,
       criticalConsumersReady:true,
       criticalPathHealth:{paths:pathHealth,matchingTopology:matchingTopology},
       consumerState:$state[0],
       matchingCommittedOffsets:{topic:"matching.commands", partitions:offsets(20)}}]
  }
' >"$fixture_dir/critical-query-isolation-probe.json"

cat >"$fixture_dir/baseline.json" <<'JSON'
{"order":{"data":{"orderId":"order-1","state":"FILLED","updatedAtUnixMs":100}},"executions":{"data":[{"executionId":"execution-1","orderId":"order-1","executedAtUnixMs":200}]},"accountSummary":{"data":{"accountId":"account-1","lifecycleState":"ACTIVE","updatedAtUnixMs":300}},"marketReference":{"data":{"tradingDay":"2026-08-31","artifactId":"artifact-1","venueMic":"XTAI","symbol":"1101","updatedAtUnixMs":400}},"freshness":{"partitions":[{"sourceTopic":"account.lifecycle","partition":0,"lastProcessedOffset":4,"recoveryState":"READY"},{"sourceTopic":"matching.events","partition":0,"lastProcessedOffset":9,"recoveryState":"READY"}]}}
JSON
cp "$fixture_dir/baseline.json" "$fixture_dir/redis-outage.json"
cp "$fixture_dir/baseline.json" "$fixture_dir/rebuilt.json"
cat >"$fixture_dir/restoration.json" <<'JSON'
{"redisKeysPresent":true,"queryServiceReady":true,"queryOutageObserved":true,"matchingReady":15,"criticalConsumersReady":true}
JSON

cat >"$fixture_dir/active-processing-liveness.json" <<'JSON'
{
  "status":"PROVEN",
  "startedAtEpochMs":1000,
  "completedAtEpochMs":1600,
  "elapsedMilliseconds":600,
  "timeoutSeconds":30,
  "queryOutage":{"queryPodCount":0,"queryPodNames":[]},
  "gatewayOpen":{"accepted":true,"gateState":"OPEN","occurredAt":"2026-08-31T00:00:00Z"},
  "timeInForce":"3",
  "fixSubmission":{
    "accountId":"00000000-0000-0000-0000-000000000001",
    "clOrdId":"QUERY-LIVE-1",
    "orderId":"O-QUERY-LIVE-1",
    "execType":"A",
    "ordStatus":"A",
    "timeInForce":"3",
    "terminalExecType":"4",
    "terminalOrdStatus":"4",
    "sentAtEpochMs":1100
  },
  "riskAdmission":{
    "commandId":"00000000-0000-0000-0000-000000000003",
    "orderId":"00000000-0000-0000-0000-000000000002",
    "accountId":"00000000-0000-0000-0000-000000000001",
    "clOrdId":"QUERY-LIVE-1",
    "state":"ACCEPTED",
    "routingPartition":4
  },
  "matchingEvent":{
    "topic":"matching.events",
    "partition":4,
    "startOffset":100,
    "offset":101,
    "eventId":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "eventType":"MATCHING_EVENT_TYPE_ORDER_CANCELLED",
    "sourceCommandId":"00000000-0000-0000-0000-000000000003",
    "orderId":"00000000-0000-0000-0000-000000000002"
  },
  "observerVerdict":{"status":"PASS"},
  "orderProjection":{
    "orderId":"00000000-0000-0000-0000-000000000002",
    "status":"CANCELLED",
    "lastEventId":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  },
  "accountReservation":{
    "orderId":"00000000-0000-0000-0000-000000000002",
    "accountId":"00000000-0000-0000-0000-000000000001",
    "status":"RESERVATION_STATUS_RELEASED",
    "remainingQuantity":0,
    "filledQuantity":0,
    "reservedNotional":0
  },
  "fixDeliveryIntent":{
    "eventId":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "execType":"4",
    "ordStatus":"4",
    "status":"SENT"
  },
  "marketData":{
    "partition":4,
    "lastProcessedOffset":101,
    "recoveryState":"READY",
    "inboxCount":1
  },
  "consumerState":{
    "persistenceQuarantines":0,
    "accountQuarantines":0,
    "quickfixQuarantines":0,
    "persistenceQuarantineHistory":0,
    "accountQuarantineHistory":0,
    "quickfixQuarantineHistory":0,
    "quickfixPendingIntents":0,
    "activeMatchingOrders":0,
    "marketDataDeadLetters":0,
    "marketDataProgress":[{"partition_id":4,"last_processed_offset":101,"recovery_state":"READY"}]
  },
  "exactInboxCounts":{"persistence":1,"account":1,"quickfix":1,"marketData":1}
}
JSON

evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/verdict.json"
jq -e '.status == "PASS" and (.checks | all(.passed == true))' \
  "$fixture_dir/verdict.json" >/dev/null
jq -e '.activeProcessingLiveness.status == "PROVEN"' \
  "$fixture_dir/verdict.json" >/dev/null

jq '{postActiveEvent:true}' "$fixture_dir/critical-before.json" \
  >"$fixture_dir/critical-after.json"
if ! evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/after-mutation-verdict.json"; then
  printf 'query-service verdict incorrectly required critical-after equality after active event\n' >&2
  exit 1
fi
jq -e '.checks[] | select(.name == "criticalPathIsolation") | .passed == true' \
  "$fixture_dir/after-mutation-verdict.json" >/dev/null

jq '.matchingEvent.eventType = "MATCHING_EVENT_TYPE_TRADE_EXECUTED"' \
  "$fixture_dir/active-processing-liveness.json" \
  >"$fixture_dir/active-invalid-event.json"
mv "$fixture_dir/active-invalid-event.json" "$fixture_dir/active-processing-liveness.json"
if evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/active-event-failure.json"; then
  printf 'query-service verdict accepted a non-terminal active Matching Event\n' >&2
  exit 1
fi
jq -e '.status == "FAIL"
  and .activeProcessingLiveness.status == "NOT_PROVEN"
  and any(.checks[]; .name == "activeProcessingLiveness" and .passed == false)' \
  "$fixture_dir/active-event-failure.json" >/dev/null

jq '.matchingEvent.eventType = "MATCHING_EVENT_TYPE_ORDER_CANCELLED"' \
  "$fixture_dir/active-processing-liveness.json" \
  >"$fixture_dir/active-valid-again.json"
rm "$fixture_dir/active-processing-liveness.json"
if evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/active-missing-failure.json"; then
  printf 'query-service verdict accepted missing active liveness evidence\n' >&2
  exit 1
fi
jq -e '.status == "FAIL" and .activeProcessingLiveness.status == "NOT_PROVEN"' \
  "$fixture_dir/active-missing-failure.json" >/dev/null
mv "$fixture_dir/active-valid-again.json" "$fixture_dir/active-processing-liveness.json"

jq '.samples[1].sampleIndex = 0' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-duplicate-index.json"
cp "$fixture_dir/critical-query-isolation-probe-duplicate-index.json" \
  "$fixture_dir/critical-query-isolation-probe.json"
if evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/duplicate-index-failure.json"; then
  printf 'query-service verdict accepted duplicate probe sample indexes\n' >&2
  exit 1
fi
jq -e '.status == "FAIL" and any(.checks[];
  .name == "criticalPathIsolationUnderQuiescence" and .passed == false)' \
  "$fixture_dir/duplicate-index-failure.json" >/dev/null
jq '.samples[1].sampleIndex = 1' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-restored-index.json"
mv "$fixture_dir/critical-query-isolation-probe-restored-index.json" \
  "$fixture_dir/critical-query-isolation-probe.json"

jq '.samples[1].sampleIndex = 2' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-missing-index.json"
cp "$fixture_dir/critical-query-isolation-probe-missing-index.json" \
  "$fixture_dir/critical-query-isolation-probe.json"
if evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/missing-index-failure.json"; then
  printf 'query-service verdict accepted a missing probe sample index\n' >&2
  exit 1
fi
jq -e '.status == "FAIL" and any(.checks[];
  .name == "criticalPathIsolationUnderQuiescence" and .passed == false)' \
  "$fixture_dir/missing-index-failure.json" >/dev/null
jq '.samples[1].sampleIndex = 1' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-restored-missing-index.json"
mv "$fixture_dir/critical-query-isolation-probe-restored-missing-index.json" \
  "$fixture_dir/critical-query-isolation-probe.json"

jq '.samples[1].criticalPathHealth.paths[0].readyReplicas = 0' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-unhealthy-path.json"
cp "$fixture_dir/critical-query-isolation-probe-unhealthy-path.json" \
  "$fixture_dir/critical-query-isolation-probe.json"
if evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/path-health-failure.json"; then
  printf 'query-service verdict accepted an unhealthy critical path health sample\n' >&2
  exit 1
fi
jq -e '.status == "FAIL" and any(.checks[];
  .name == "criticalPathIsolationUnderQuiescence" and .passed == false)' \
  "$fixture_dir/path-health-failure.json" >/dev/null
jq '.samples[1].criticalPathHealth.paths[0].readyReplicas = 1' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-restored-path.json"
mv "$fixture_dir/critical-query-isolation-probe-restored-path.json" \
  "$fixture_dir/critical-query-isolation-probe.json"

jq '.samples[1].criticalPathHealth.paths[0].pods[0].uid = "replacement-uid"' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-restarted-path.json"
cp "$fixture_dir/critical-query-isolation-probe-restarted-path.json" \
  "$fixture_dir/critical-query-isolation-probe.json"
if evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/path-replacement-failure.json"; then
  printf 'query-service verdict accepted a replaced critical path Pod\n' >&2
  exit 1
fi
jq -e '.status == "FAIL" and any(.checks[];
  .name == "criticalPathIsolationUnderQuiescence" and .passed == false)' \
  "$fixture_dir/path-replacement-failure.json" >/dev/null
jq '.samples[1].criticalPathHealth.paths[0].pods[0].uid = "admission-uid"' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-restored-replacement.json"
mv "$fixture_dir/critical-query-isolation-probe-restored-replacement.json" \
  "$fixture_dir/critical-query-isolation-probe.json"

jq '.samples[1].matchingReady = 14' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-invalid.json"
cp "$fixture_dir/critical-query-isolation-probe-invalid.json" \
  "$fixture_dir/critical-query-isolation-probe.json"
if evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/isolation-failure.json"; then
  printf 'query-service verdict accepted an unhealthy isolation probe\n' >&2
  exit 1
fi
jq -e '.status == "FAIL" and any(.checks[];
  .name == "criticalPathIsolationUnderQuiescence" and .passed == false)' \
  "$fixture_dir/isolation-failure.json" >/dev/null
jq '.samples[1].matchingReady = 15' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-restored.json"
mv "$fixture_dir/critical-query-isolation-probe-restored.json" \
  "$fixture_dir/critical-query-isolation-probe.json"

jq '.samples[1].matchingCommittedOffsets.partitions[0].committedOffset = 19' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-decreasing.json"
cp "$fixture_dir/critical-query-isolation-probe-decreasing.json" \
  "$fixture_dir/critical-query-isolation-probe.json"
if evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/offset-failure.json"; then
  printf 'query-service verdict accepted a regressing Matching offset\n' >&2
  exit 1
fi
jq -e '.status == "FAIL" and any(.checks[];
  .name == "criticalPathIsolationUnderQuiescence" and .passed == false)' \
  "$fixture_dir/offset-failure.json" >/dev/null
jq '.samples[1].matchingCommittedOffsets.partitions[0].committedOffset = 20' \
  "$fixture_dir/critical-query-isolation-probe.json" \
  >"$fixture_dir/critical-query-isolation-probe-restored-again.json"
mv "$fixture_dir/critical-query-isolation-probe-restored-again.json" \
  "$fixture_dir/critical-query-isolation-probe.json"

jq '.order.data.updatedAtUnixMs = 101
  | .executions.data[0].executedAtUnixMs = 201
  | .accountSummary.data.updatedAtUnixMs = 301
  | .marketReference.data.updatedAtUnixMs = 401' \
  "$fixture_dir/baseline.json" >"$fixture_dir/replayed-times.json"
cp "$fixture_dir/replayed-times.json" "$fixture_dir/rebuilt.json"
if ! evaluate_query_service_verdict "$fixture_dir" "$fixture_dir/replayed-times-verdict.json"; then
  printf 'query-service verdict treated observation timestamps as business divergence\n' >&2
  exit 1
fi
jq -e '.status == "PASS" and any(.checks[];
  .name == "deterministicRebuild" and .passed == true)' \
  "$fixture_dir/replayed-times-verdict.json" >/dev/null

cp "$fixture_dir/baseline.json" "$fixture_dir/rebuilt.json"
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

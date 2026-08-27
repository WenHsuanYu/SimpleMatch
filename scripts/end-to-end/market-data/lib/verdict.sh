#!/usr/bin/env bash

# Evaluates retained evidence without reaching into certification orchestration.
evaluate_market_data_verdict() {
  local evidence_dir="$1"
  local destination="$2"
  local critical_before="$evidence_dir/critical-before.json"
  local critical_after="$evidence_dir/critical-after.json"
  local baseline="$evidence_dir/baseline-snapshot.json"
  local rebuilt="$evidence_dir/rebuilt-snapshot.json"
  local restoration="$evidence_dir/restoration.json"

  jq -n \
    --slurpfile criticalBefore "$critical_before" \
    --slurpfile criticalAfter "$critical_after" \
    --slurpfile baseline "$baseline" \
    --slurpfile rebuilt "$rebuilt" \
    --slurpfile restoration "$restoration" '
      def business_view:
        {
          sourceMatchingEventId,
          venueMic,
          symbol,
          instrumentSequence,
          sourcePartitionId,
          sourceKafkaOffset,
          completeSnapshot,
          lastTrade,
          bids,
          asks
        };
      [
        {
          name:"criticalConsumerIsolation",
          passed:($criticalBefore[0] == $criticalAfter[0])
        },
        {
          name:"deterministicRebuild",
          passed:(
            ($baseline[0] | business_view) == ($rebuilt[0] | business_view)
            and $baseline[0].completeSnapshot == true
            and $rebuilt[0].completeSnapshot == true
          )
        },
        {
          name:"redisRepair",
          passed:($restoration[0].redisRepaired == true)
        },
        {
          name:"projectionRestored",
          passed:($restoration[0].projectionReady == true)
        },
        {
          name:"streamerRestored",
          passed:($restoration[0].streamerReady == true)
        },
        {
          name:"matchingIsolation",
          passed:($restoration[0].matchingReady == 15)
        },
        {
          name:"criticalConsumersRestored",
          passed:($restoration[0].criticalConsumersReady == true)
        }
      ] as $checks
      | {
          status:(if all($checks[]; .passed) then "PASS" else "FAIL" end),
          checks:$checks
        }
    ' >"$destination"

  jq -e '.status == "PASS"' "$destination" >/dev/null
}

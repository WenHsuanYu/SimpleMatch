#!/usr/bin/env bash

# Evaluates retained query-service evidence without invoking orchestration.
evaluate_query_service_verdict() {
  local evidence_dir="$1"
  local destination="$2"

  jq -n \
    --slurpfile criticalBefore "$evidence_dir/critical-before.json" \
    --slurpfile criticalAfter "$evidence_dir/critical-after.json" \
    --slurpfile baseline "$evidence_dir/baseline.json" \
    --slurpfile fallback "$evidence_dir/redis-outage.json" \
    --slurpfile rebuilt "$evidence_dir/rebuilt.json" \
    --slurpfile restoration "$evidence_dir/restoration.json" '
      def business_view:
        {
          order:.order.data,
          executions:.executions.data,
          accountSummary:.accountSummary.data,
          marketReference:.marketReference.data
        };
      def healthy_freshness:
        (.freshness.partitions | length) > 0
        and ([.freshness.partitions[].sourceTopic] | unique | sort)
          == ["account.lifecycle", "matching.events"]
        and all(.freshness.partitions[];
          .lastProcessedOffset >= 0 and .recoveryState == "READY");
      [
        {
          name:"deterministicRebuild",
          passed:(
            ($baseline[0] | business_view) == ($rebuilt[0] | business_view)
            and ($baseline[0].executions.data | length) > 0
          )
        },
        {
          name:"postgresFallback",
          passed:(($baseline[0] | business_view) == ($fallback[0] | business_view))
        },
        {
          name:"freshnessRestored",
          passed:(($baseline[0] | healthy_freshness) and ($rebuilt[0] | healthy_freshness))
        },
        {
          name:"marketReferenceIdentity",
          passed:(
            ($rebuilt[0].marketReference.data.tradingDay | length) == 10
            and ($rebuilt[0].marketReference.data.artifactId | length) > 0
          )
        },
        {
          name:"redisRebuilt",
          passed:($restoration[0].redisKeysPresent == true)
        },
        {
          name:"queryServiceRestored",
          passed:($restoration[0].queryServiceReady == true)
        },
        {
          name:"criticalPathIsolation",
          passed:(
            $criticalBefore[0] == $criticalAfter[0]
            and $restoration[0].matchingReady == 15
            and $restoration[0].criticalConsumersReady == true
          )
        }
      ] as $checks
      | {
          status:(if all($checks[]; .passed) then "PASS" else "FAIL" end),
          checks:$checks
        }
    ' >"$destination"

  jq -e '.status == "PASS"' "$destination" >/dev/null
}

#!/usr/bin/env bash

# Evaluates retained query-service evidence without invoking orchestration.
evaluate_query_service_verdict() {
  local evidence_dir="$1"
  local destination="$2"

  jq -n \
    --slurpfile criticalBefore "$evidence_dir/critical-before.json" \
    --slurpfile criticalDuring "$evidence_dir/critical-during-query-outage.json" \
    --slurpfile criticalAfter "$evidence_dir/critical-after.json" \
    --slurpfile isolationProbe "$evidence_dir/critical-query-isolation-probe.json" \
    --slurpfile baseline "$evidence_dir/baseline.json" \
    --slurpfile fallback "$evidence_dir/redis-outage.json" \
    --slurpfile rebuilt "$evidence_dir/rebuilt.json" \
    --slurpfile restoration "$evidence_dir/restoration.json" '
      def business_view:
        {
          order:(.order.data | del(.updatedAtUnixMs)),
          executions:(.executions.data | map(del(.executedAtUnixMs))),
          accountSummary:(.accountSummary.data | del(.updatedAtUnixMs)),
          marketReference:(.marketReference.data | del(.updatedAtUnixMs))
        };
      def healthy_freshness:
        (.freshness.partitions | length) > 0
        and ([.freshness.partitions[].sourceTopic] | unique | sort)
          == ["account.lifecycle", "matching.events"]
        and all(.freshness.partitions[];
          .lastProcessedOffset >= 0 and .recoveryState == "READY");
      def healthy_matching_topology:
        . as $topology
        | ($topology.statefulset) as $statefulset
        | ([range(0; 15) | tostring]) as $expectedOrdinals
        | ([range(0; 15) | ($statefulset.name + "-" + tostring)]) as $expectedPodNames
        | ($statefulset.name | type == "string" and length > 0)
          and ($statefulset.uid | type == "string" and length > 0)
          and $statefulset.desiredReplicas == 15
          and $statefulset.readyReplicas == 15
          and ($statefulset.currentRevision | type == "string" and length > 0)
          and $statefulset.currentRevision == $statefulset.updateRevision
          and $topology.expectedOrdinals == $expectedOrdinals
          and ($topology.pods | type == "array" and length == 15)
          and ([$topology.pods[].name] | sort) == ($expectedPodNames | sort)
          and ([$topology.pods[].ordinal] | sort_by(tonumber)) == $expectedOrdinals
          and ([$topology.pods[].uid] | unique | length) == 15
          and all($topology.pods[]; . as $pod
            | ($pod.ready == true)
            and ($pod.uid | type == "string" and length > 0)
            and ($pod.node | type == "string" and length > 0)
            and $pod.ownerStatefulSet == true
            and $pod.controllerRevisionHash == $statefulset.currentRevision
            and $pod.pvc == ("matching-baseline-" + $statefulset.name + "-" + $pod.ordinal)
            and $pod.pvcPhase == "Bound"
            and (($pod.pvcAccessModes | type == "array")
              and (($pod.pvcAccessModes | index("ReadWriteOncePod")) != null))
            and ($pod.pv | type == "string" and length > 0)
            and (($pod.pvNodeAffinityNodes | type == "array")
              and (($pod.pvNodeAffinityNodes | index($pod.node)) != null))
          )
          and ($topology.unownedMatchingPods | type == "array" and length == 0)
          and ($topology.unexpectedMatchingPods | type == "array" and length == 0);
      def healthy_path_health:
        . as $health
        | if ($health.paths | type) != "array" then false
          else
            ($health.paths | map(.path) | sort) as $names
            | $names ==
                ["account", "admission", "marketData", "matching", "persistence", "quickfix", "reservation"]
            and ([ $health.paths[] | {path,resource}] | sort_by(.path)) == [
              {path:"account",resource:"deployment/account-service"},
              {path:"admission",resource:"deployment/risk-service"},
              {path:"marketData",resource:"deployment/market-data-projection"},
              {path:"matching",resource:"statefulset/matching"},
              {path:"persistence",resource:"deployment/persistence"},
              {path:"quickfix",resource:"statefulset/quickfix-gateway"},
              {path:"reservation",resource:"deployment/account-service"}
            ]
            and ([ $health.paths[]
                   | select(.path == "matching")
                   | .desiredReplicas ] == [15])
            and all($health.paths[];
              ((.desiredReplicas | type) == "number" and .desiredReplicas > 0)
              and .readyReplicas == .desiredReplicas
              and .podCount == .desiredReplicas
              and .readyPodCount == .desiredReplicas
              and ((.pods | type) == "array" and (.pods | length) == .desiredReplicas)
              and all(.pods[];
                .phase == "Running"
                and .ready == true
                and (.uid | type == "string" and length > 0)
                and ((.restartCount | type) == "number" and .restartCount >= 0)
              )
            )
            and ($health.matchingTopology | healthy_matching_topology)
          end;
      def healthy_quiescent_isolation_probe($expected_state):
        . as $probe
        | ($probe.probeDurationSeconds | type == "number" and . >= 2)
        and ($probe.sampleCount | type == "number" and . == $probe.probeDurationSeconds)
        and (($probe.samples | length) == $probe.sampleCount)
        and ($probe.probeStartedEpochMs | type == "number" and . >= 0)
        and ($probe.probeCompletedEpochMs | type == "number"
          and . >= $probe.probeStartedEpochMs)
        and ($probe.elapsedMilliseconds | type == "number"
          and . == ($probe.probeCompletedEpochMs - $probe.probeStartedEpochMs)
          and . <= ($probe.probeDurationSeconds * 1000))
        and ($probe.commandTimeoutSeconds | type == "number" and . > 0 and . <= 30)
        and ([$probe.samples | sort_by(.sampleIndex) | .[].sampleIndex]
          == [range(0; $probe.sampleCount)])
        and all($probe.samples[];
          (.criticalPathHealth | healthy_path_health)
        )
        and all($probe.samples[];
          .queryPodCount == 0
          and .matchingReady == 15
          and .criticalConsumersReady == true
          and .consumerState == $expected_state
          and .matchingCommittedOffsets.topic == "matching.commands"
          and (.matchingCommittedOffsets.partitions | length) == 15
          and ([.matchingCommittedOffsets.partitions[].partition] == [range(0; 15)])
          and all(.matchingCommittedOffsets.partitions[]; .committedOffset >= 0)
        )
        and all(range(1; ($probe.samples | length));
          . as $index
          | all($probe.samples[0].criticalPathHealth.paths[];
            . as $baselinePath
            | ([ $probe.samples[$index].criticalPathHealth.paths[]
                | select(.path == $baselinePath.path) ][0]) as $currentPath
            | ($currentPath != null)
              and $currentPath.restartCount == $baselinePath.restartCount
              and ([ $currentPath.pods[]?.uid ] | sort)
                == ([ $baselinePath.pods[]?.uid ] | sort)
          )
        )
        and all(range(1; ($probe.samples | length));
          . as $index
          | all(range(0; 15);
            . as $partition
            | ([ $probe.samples[$index - 1].matchingCommittedOffsets.partitions[]
                | select(.partition == $partition) ][0].committedOffset)
              <=
              ([ $probe.samples[$index].matchingCommittedOffsets.partitions[]
                | select(.partition == $partition) ][0].committedOffset)
          )
        );
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
          name:"criticalPathIsolationUnderQuiescence",
          passed:($isolationProbe[0] | healthy_quiescent_isolation_probe($criticalBefore[0]))
        },
        {
          name:"criticalPathIsolation",
          passed:(
            $criticalBefore[0] == $criticalAfter[0]
            and $criticalBefore[0] == $criticalDuring[0]
            and $restoration[0].queryOutageObserved == true
            and $restoration[0].matchingReady == 15
            and $restoration[0].criticalConsumersReady == true
          )
        }
      ] as $checks
      | {
          status:(if all($checks[]; .passed) then "PASS" else "FAIL" end),
          activeProcessingLiveness:{
            status:"NOT_PROVEN",
            reason:"the outage probe is deliberately quiescent and submits no new public event"
          },
          checks:$checks
        }
    ' >"$destination"

  jq -e '.status == "PASS"' "$destination" >/dev/null
}

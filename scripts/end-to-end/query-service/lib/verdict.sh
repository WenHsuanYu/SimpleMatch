#!/usr/bin/env bash

# Evaluates retained query-service evidence without invoking orchestration.
evaluate_query_service_verdict() {
  local evidence_dir="$1"
  local destination="$2"
  local active_evidence_args=(--argjson activeEvidence '[]')
  if [[ -s "$evidence_dir/active-processing-liveness.json" ]]; then
    active_evidence_args=(--slurpfile activeEvidence "$evidence_dir/active-processing-liveness.json")
  fi

  jq -n \
    --slurpfile criticalBefore "$evidence_dir/critical-before.json" \
    --slurpfile criticalDuring "$evidence_dir/critical-during-query-outage.json" \
    --slurpfile queryOutage "$evidence_dir/query-outage.json" \
    --slurpfile isolationProbe "$evidence_dir/critical-query-isolation-probe.json" \
    --slurpfile baseline "$evidence_dir/baseline.json" \
    --slurpfile fallback "$evidence_dir/redis-outage.json" \
    --slurpfile rebuilt "$evidence_dir/rebuilt.json" \
    --slurpfile restoration "$evidence_dir/restoration.json" \
    "${active_evidence_args[@]}" '
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
          and . >= 0)
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

      def healthy_active_liveness($query_outage):
        . as $active
        | ($active.status == "PROVEN")
        and ($query_outage.queryPodCount == 0)
        and ($active.queryOutage.queryPodCount == 0)
        and ($active.gatewayOpen.accepted == true)
        and ($active.gatewayOpen.gateState == "OPEN")
        and ($active.timeInForce == "3")
        and ($active.startedAtEpochMs | type == "number" and . >= 0)
        and ($active.completedAtEpochMs | type == "number"
          and . >= $active.startedAtEpochMs)
        and ($active.elapsedMilliseconds | type == "number"
          and . == ($active.completedAtEpochMs - $active.startedAtEpochMs)
          and . >= 0
          and . <= ($active.timeoutSeconds * 1000))
        and ($active.timeoutSeconds | type == "number" and . > 0 and . <= 300)
        and ($active.fixSubmission.timeInForce == "3")
        and ($active.fixSubmission.execType == "A")
        and ($active.fixSubmission.ordStatus == "A")
        and ($active.fixSubmission.terminalExecType == "4")
        and ($active.fixSubmission.terminalOrdStatus == "4")
        and ($active.fixSubmission.sentAtEpochMs | type == "number"
          and . >= $active.startedAtEpochMs
          and . <= $active.completedAtEpochMs)
        and ($active.fixSubmission.accountId == $active.riskAdmission.accountId)
        and ($active.fixSubmission.clOrdId == $active.riskAdmission.clOrdId)
        and ($active.fixSubmission.orderId | type == "string" and startswith("O-"))
        and ($active.riskAdmission.state == "ACCEPTED")
        and ($active.riskAdmission.routingPartition | type == "number"
          and . >= 0 and . <= 14)
        and ($active.matchingEvent.topic == "matching.events")
        and ($active.matchingEvent.partition == $active.riskAdmission.routingPartition)
        and ($active.matchingEvent.startOffset | type == "number" and . >= 0)
        and ($active.matchingEvent.offset | type == "number"
          and . >= $active.matchingEvent.startOffset)
        and ($active.matchingEvent.eventId | type == "string"
          and test("^[0-9a-f]{64}$"))
        and ($active.matchingEvent.eventType == "MATCHING_EVENT_TYPE_ORDER_CANCELLED")
        and ($active.matchingEvent.sourceCommandId == $active.riskAdmission.commandId)
        and ($active.matchingEvent.orderId == $active.riskAdmission.orderId)
        and ($active.observerVerdict.status == "PASS")
        and ($active.orderProjection.orderId == $active.riskAdmission.orderId)
        and ($active.orderProjection.status == "CANCELLED")
        and ($active.orderProjection.lastEventId == $active.matchingEvent.eventId)
        and ($active.accountReservation.orderId == $active.riskAdmission.orderId)
        and ($active.accountReservation.accountId == $active.riskAdmission.accountId)
        and ($active.accountReservation.status == "RESERVATION_STATUS_RELEASED")
        and ($active.accountReservation.remainingQuantity == 0)
        and ($active.accountReservation.reservedNotional == 0)
        and ($active.fixDeliveryIntent.status == "SENT")
        and ($active.fixDeliveryIntent.eventId == $active.matchingEvent.eventId)
        and ($active.fixDeliveryIntent.execType == "4")
        and ($active.fixDeliveryIntent.ordStatus == "4")
        and ($active.marketData.partition == $active.matchingEvent.partition)
        and ($active.marketData.lastProcessedOffset >= $active.matchingEvent.offset)
        and ($active.marketData.recoveryState == "READY")
        and ($active.marketData.inboxCount == 1)
        and ($active.exactInboxCounts.persistence == 1)
        and ($active.exactInboxCounts.account == 1)
        and ($active.exactInboxCounts.quickfix == 1)
        and ($active.exactInboxCounts.marketData == 1)
        and ($active.consumerState.persistenceQuarantines == 0)
        and ($active.consumerState.accountQuarantines == 0)
        and ($active.consumerState.quickfixQuarantines == 0)
        and ($active.consumerState.persistenceQuarantineHistory == 0)
        and ($active.consumerState.accountQuarantineHistory == 0)
        and ($active.consumerState.quickfixQuarantineHistory == 0)
        and ($active.consumerState.quickfixPendingIntents == 0)
        and ($active.consumerState.activeMatchingOrders == 0)
        and ($active.consumerState.marketDataDeadLetters == 0)
        and any(($active.consumerState.marketDataProgress // [])[];
          .partition_id == $active.matchingEvent.partition
          and .last_processed_offset >= $active.matchingEvent.offset
          and .recovery_state == "READY");
      ($activeEvidence
        | if type == "array" and length > 0 then .[0] else {} end) as $active
      | ($active | healthy_active_liveness($queryOutage[0])) as $activePassed
      | [
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
          name:"activeProcessingLiveness",
          passed:$activePassed
        },
        {
          name:"criticalPathIsolationUnderQuiescence",
          passed:($isolationProbe[0] | healthy_quiescent_isolation_probe($criticalBefore[0]))
        },
        {
          name:"criticalPathIsolation",
          passed:(
            $criticalBefore[0] == $criticalDuring[0]
            and $restoration[0].queryOutageObserved == true
            and $restoration[0].matchingReady == 15
            and $restoration[0].criticalConsumersReady == true
          )
        }
      ] as $checks
      | {
          status:(if all($checks[]; .passed) then "PASS" else "FAIL" end),
          activeProcessingLiveness:{
            status:(if $activePassed then "PROVEN" else "NOT_PROVEN" end),
            reason:(if $activePassed
              then "a public IOC FIX order was admitted and processed while query-service had zero Pods"
              else "active processing evidence is missing or does not prove the public IOC event path"
              end)
          },
          checks:$checks
        }
    ' >"$destination"

  jq -e '.status == "PASS"' "$destination" >/dev/null
}

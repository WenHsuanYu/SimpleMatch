#!/usr/bin/env bash

# Kubernetes, Kafka, PostgreSQL, and test-fixture access for the deployed test.
# The caller provides context, namespace, evidence_dir, timeout_seconds, and repo_root.

kns() {
  local -a kubectl_args=(--context "$context" -n "$namespace")
  if [[ "${kubernetes_request_timeout_seconds:-}" =~ ^[1-9][0-9]*$ ]]; then
    kubectl_args+=(--request-timeout="${kubernetes_request_timeout_seconds}s")
    timeout --foreground --signal=TERM --kill-after=2s \
      "${kubernetes_request_timeout_seconds}s" kubectl "${kubectl_args[@]}" "$@"
  else
    kubectl "${kubectl_args[@]}" "$@"
  fi
}

workload_replicas() {
  local kind="$1"
  local name="$2"
  kns get "$kind/$name" -o jsonpath='{.spec.replicas}'
}

wait_deployment_replicas() {
  local name="$1"
  local replicas="$2"
  if (( replicas == 0 )); then
    for _ in $(seq 1 "$timeout_seconds"); do
      local count
      count="$(kns get pods -l "app.kubernetes.io/name=$name" -o json | jq '.items | length')"
      (( count == 0 )) && return 0
      sleep 1
    done
    return 1
  fi
  kns rollout status "deployment/$name" --timeout="${timeout_seconds}s" >/dev/null
}

wait_statefulset_replicas() {
  local name="$1"
  local replicas="$2"
  if (( replicas == 0 )); then
    for _ in $(seq 1 "$timeout_seconds"); do
      local count
      count="$(kns get pods -l "app.kubernetes.io/name=$name" -o json | jq '.items | length')"
      (( count == 0 )) && return 0
      sleep 1
    done
    return 1
  fi
  kns rollout status "statefulset/$name" --timeout="${timeout_seconds}s" >/dev/null
}

scale_deployment() {
  local name="$1"
  local replicas="$2"
  kns scale "deployment/$name" --replicas="$replicas" >/dev/null || return 1
  wait_deployment_replicas "$name" "$replicas" || return 1
}

scale_statefulset() {
  local name="$1"
  local replicas="$2"
  kns scale "statefulset/$name" --replicas="$replicas" >/dev/null
  wait_statefulset_replicas "$name" "$replicas" ||
    die "statefulset/$name did not reach $replicas replicas"
}

postgres_pod() {
  kns get pods -l app.kubernetes.io/name=postgres -o json |
    jq -r '.items | if length == 1 then .[0].metadata.name else empty end'
}

kafka_pod() {
  kns get pods \
    -l 'app.kubernetes.io/name=kafka,app.kubernetes.io/component=broker' \
    -o json |
    jq -r '[.items[].metadata.name] | sort | .[0] // empty'
}

capture_topic_offsets() {
  local topic="$1"
  local destination="$2"
  local stderr_path="${3:-${destination%.json}.stderr.log}"
  local offset_time="${4:-}"
  local -a offset_args=(--bootstrap-server kafka:9092 --topic "$topic")
  local broker
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || return 1
  [[ -n "$offset_time" ]] && offset_args+=(--time "$offset_time")

  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-get-offsets.sh \
      "${offset_args[@]}" 2>"$stderr_path" |
    jq -eRn --arg topic "$topic" '
      [inputs
        | select(length > 0)
        | split(":")
        | {partition:(.[1] | tonumber), offset:(.[2] | tonumber)}]
      | sort_by(.partition)
      | if length == 15 and ([.[].partition] == [range(0; 15)]) then
          {topic:$topic, partitions:.}
        else
          error("topic offset snapshot must contain partitions 0 through 14")
        end
    ' >"$destination"
}

offset_for_partition() {
  local snapshot="$1"
  local partition="$2"
  jq -er --argjson partition "$partition" '
    [.partitions[] | select(.partition == $partition)] as $matches
    | if ($matches | length) == 1 then $matches[0].offset
      else error("topic partition offset must exist exactly once") end
    | select(type == "number" and . >= 0)
  ' "$snapshot"
}

capture_matching_committed_offsets() {
  local destination="$1"
  local raw_output="${destination%.json}.txt"
  local stderr_path="${destination%.json}.stderr.log"
  local broker
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || return 1

  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server kafka:9092 \
      --all-groups \
      --describe >"$raw_output" 2>"$stderr_path" || return 1

  normalize_matching_committed_offsets <"$raw_output" >"$destination" || return 1
  jq -e '
    (.partitions | length) == 15
    and ([.partitions[].partition] == [range(0; 15)])
    and all(.partitions[]; .committedOffset >= 0)
  ' "$destination" >/dev/null
}

matching_ready_replicas() {
  kns get pods -l app.kubernetes.io/name=matching -o json |
    jq '[.items[] | select(any(.status.conditions[]?;
      .type == "Ready" and .status == "True"))] | length'
}

capture_matching_fleet_topology() {
  local destination="$1"
  local statefulset_name="${SIMPLEMATCH_MATCHING_STATEFULSET:-matching}"
  local statefulset_json pods_json pvcs_json pvs_json all_pods_json

  statefulset_json="$(kns get "statefulset/$statefulset_name" -o json)" || return 1
  pods_json="$(kns get pods -l app.kubernetes.io/name=matching -o json)" || return 1
  pvcs_json="$(kns get pvc -o json)" || return 1
  pvs_json="$(kns get pv -o json)" || return 1
  all_pods_json="$(kns get pods -o json)" || return 1

  jq -n \
    --argjson statefulset "$statefulset_json" \
    --argjson pods "$pods_json" \
    --argjson pvcs "$pvcs_json" \
    --argjson pvs "$pvs_json" \
    --argjson allPods "$all_pods_json" '
      def items($resource): ($resource.items // []);
      def ready:
        any(.status.conditions[]?; .type == "Ready" and .status == "True");
      def node_affinity_nodes:
        [.spec.nodeAffinity.required.nodeSelectorTerms[]?.matchExpressions[]?
          | select(.key == "kubernetes.io/hostname") | .values[]?];

      ($statefulset.metadata.name // "") as $statefulsetName
      | ([range(0; 15) | tostring]) as $expectedOrdinals
      | ([range(0; 15) | ($statefulsetName + "-" + tostring)]) as $expectedPodNames
      | (items($pvcs) | map({key:(.metadata.name // ""),value:.}) | from_entries) as $pvcByName
      | (items($pvs) | map({key:(.metadata.name // ""),value:.}) | from_entries) as $pvByName
      | (items($pods) | map(select(.metadata.labels["app.kubernetes.io/name"] == "matching"))) as $matchingPods
      | {
          statefulset:{
            name:$statefulsetName,
            uid:($statefulset.metadata.uid // ""),
            desiredReplicas:($statefulset.spec.replicas // 0),
            readyReplicas:($statefulset.status.readyReplicas // 0),
            currentRevision:($statefulset.status.currentRevision // ""),
            updateRevision:($statefulset.status.updateRevision // "")
          },
          expectedOrdinals:$expectedOrdinals,
          expectedPodNames:$expectedPodNames,
          pods:($matchingPods | map(
            . as $pod
            | ([.spec.volumes[]? | select(.name == "matching-baseline")
                | .persistentVolumeClaim.claimName] | .[0] // "") as $pvcName
            | ($pvcByName[$pvcName] // {}) as $pvc
            | ($pvByName[($pvc.spec.volumeName // "")] // {}) as $pv
            | {
                name:($pod.metadata.name // ""),
                uid:($pod.metadata.uid // ""),
                ordinal:($pod.metadata.labels["apps.kubernetes.io/pod-index"] // ""),
                node:($pod.spec.nodeName // ""),
                ready:($pod | ready),
                ownerStatefulSet:(any(($pod.metadata.ownerReferences // [])[];
                  .kind == "StatefulSet" and .name == $statefulsetName and .controller == true)),
                controllerRevisionHash:($pod.metadata.labels["controller-revision-hash"] // ""),
                pvc:$pvcName,
                pvcPhase:($pvc.status.phase // ""),
                pvcAccessModes:($pvc.spec.accessModes // []),
                pv:($pvc.spec.volumeName // ""),
                pvNodeAffinityNodes:($pv | node_affinity_nodes)
              }
          )),
          unownedMatchingPods:(
            items($allPods)
            | map(select(.metadata.labels["app.kubernetes.io/name"] == "matching"))
            | map(select(
                (any((.metadata.ownerReferences // [])[];
                  .kind == "StatefulSet" and .name == $statefulsetName and .controller == true)
                | not)
              ) | (.metadata.name // ""))
          ),
          unexpectedMatchingPods:(
            items($allPods)
            | map(.metadata.name // "")
            | map(. as $name
              | select(startswith($statefulsetName + "-")
                and (($expectedPodNames | index($name)) == null)))
          )
        }
    ' >"$destination"
}

matching_fleet_topology_is_healthy() {
  local topology="$1"
  jq -e '
    (.matchingTopology // .) as $topology
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
      and ($topology.unexpectedMatchingPods | type == "array" and length == 0)
  ' "$topology" >/dev/null
}

capture_critical_path_health() {
  local destination="$1"
  local records_file="${destination%.json}.ndjson"
  local topology_file="${destination%.json}.matching-topology.json"
  local path resource kind name resource_spec workload_json pods_json
  local desired_replicas ready_replicas
  local -a path_resources=(
    'admission:deployment/risk-service'
    'reservation:deployment/account-service'
    'matching:statefulset/matching'
    'persistence:deployment/persistence'
    'account:deployment/account-service'
    'quickfix:statefulset/quickfix-gateway'
    'marketData:deployment/market-data-projection'
  )

  : >"$records_file" || return 1
  for resource_spec in "${path_resources[@]}"; do
    path="${resource_spec%%:*}"
    resource="${resource_spec#*:}"
    kind="${resource%%/*}"
    name="${resource#*/}"
    workload_json="$(kns get "$resource" -o json)" || return 1
    pods_json="$(kns get pods -l "app.kubernetes.io/name=$name" -o json)" || return 1
    desired_replicas="$(jq -er '.spec.replicas | numbers' <<<"$workload_json")" || return 1
    ready_replicas="$(jq -er '.status.readyReplicas // 0 | numbers' <<<"$workload_json")" ||
      return 1
    jq -n \
      --arg path "$path" \
      --arg resource "$kind/$name" \
      --argjson desiredReplicas "$desired_replicas" \
      --argjson readyReplicas "$ready_replicas" \
      --argjson pods "$pods_json" '
        [$pods.items[]? | {
          name:(.metadata.name // ""),
          uid:(.metadata.uid // ""),
          phase:(.status.phase // ""),
          ready:any(.status.conditions[]?;
            .type == "Ready" and .status == "True"),
          restartCount:([.status.containerStatuses[]?.restartCount] | add // 0)
        }] as $podStates
        | {
            path:$path,
            resource:$resource,
            desiredReplicas:$desiredReplicas,
            readyReplicas:$readyReplicas,
            podCount:($podStates | length),
            readyPodCount:($podStates | map(select(.ready)) | length),
            restartCount:($podStates | map(.restartCount) | add // 0),
            pods:$podStates
          }
      ' >>"$records_file" || return 1
  done

  capture_matching_fleet_topology "$topology_file" || return 1
  matching_fleet_topology_is_healthy "$topology_file" || return 1
  jq -s --slurpfile matchingTopology "$topology_file" \
    '{paths:.,matchingTopology:$matchingTopology[0]}' "$records_file" >"$destination"
}

critical_path_health_is_healthy() {
  local health="$1"
  jq -e '
    (.paths | type == "array" and length == 7) as $hasExpectedShape
    | .paths as $paths
    | $hasExpectedShape
      and ([ $paths[].path ] | sort) ==
        ["account", "admission", "marketData", "matching", "persistence", "quickfix", "reservation"]
      and ([ $paths[] | {path,resource}] | sort_by(.path)) == [
        {path:"account",resource:"deployment/account-service"},
        {path:"admission",resource:"deployment/risk-service"},
        {path:"marketData",resource:"deployment/market-data-projection"},
        {path:"matching",resource:"statefulset/matching"},
        {path:"persistence",resource:"deployment/persistence"},
        {path:"quickfix",resource:"statefulset/quickfix-gateway"},
        {path:"reservation",resource:"deployment/account-service"}
      ]
      and all($paths[];
        (.desiredReplicas | type == "number" and . > 0)
        and (.readyReplicas == .desiredReplicas)
        and (.podCount == .desiredReplicas)
        and (.readyPodCount == .desiredReplicas)
        and ((.pods | length) == .desiredReplicas)
        and all(.pods[];
          .phase == "Running"
          and .ready == true
          and (.uid | type == "string" and length > 0)
          and ((.restartCount | type) == "number" and .restartCount >= 0)
        )
      )
      and ([ $paths[] | select(.path == "matching") | .desiredReplicas] == [15])
  ' "$health" >/dev/null || return 1
  matching_fleet_topology_is_healthy "$health"
}

critical_consumer_state_is_healthy() {
  local state="$1"
  jq -e '
    .persistenceQuarantines == 0
    and .accountQuarantines == 0
    and .quickfixQuarantines == 0
    and .riskQuarantines == 0
    and .quickfixPendingIntents == 0
    and .marketDataDeadLetters == 0
    and (.marketDataProgress | length) > 0
    and all(.marketDataProgress[]; .recovery_state == "READY")
  ' "$state" >/dev/null
}

capture_query_isolation_probe() {
  local destination="$1"
  local probe_seconds="${2:-5}"
  local command_timeout_seconds="${query_isolation_command_timeout_seconds:-5}"
  local samples_dir="${destination%.json}.samples"
  local samples_file="$samples_dir/samples.ndjson"
  local sample probe_started_epoch_ms probe_deadline_epoch_ms now_epoch_ms remaining_ms

  [[ "$probe_seconds" =~ ^[1-9][0-9]*$ ]] || return 1
  (( probe_seconds <= 30 )) || return 1
  [[ "$command_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || return 1
  (( command_timeout_seconds <= 30 )) || return 1
  probe_started_epoch_ms="$(date +%s%3N)" || return 1
  probe_deadline_epoch_ms=$((probe_started_epoch_ms + probe_seconds * 1000))
  mkdir -p "$samples_dir" || return 1
  : >"$samples_file" || return 1

  local kubernetes_request_timeout_seconds="$command_timeout_seconds"
  for ((sample = 0; sample < probe_seconds; sample += 1)); do
    now_epoch_ms="$(date +%s%3N)" || return 1
    (( now_epoch_ms <= probe_deadline_epoch_ms )) || return 1
    local state_file="$samples_dir/consumer-state-$sample.json"
    local outage_file="$samples_dir/query-outage-$sample.json"
    local offsets_file="$samples_dir/matching-committed-$sample.json"
    local health_file="$samples_dir/critical-path-health-$sample.json"
    local matching_ready critical_ready query_pod_count

    capture_consumer_state "$state_file" || return 1
    critical_consumer_state_is_healthy "$state_file" || return 1
    capture_critical_path_health "$health_file" || return 1
    critical_path_health_is_healthy "$health_file" || return 1
    capture_query_service_outage_state "$outage_file" || return 1
    query_pod_count="$(jq -er '.queryPodCount | select(type == "number")' "$outage_file")" ||
      return 1
    (( query_pod_count == 0 )) || return 1
    matching_ready="$(matching_ready_replicas)" || return 1
    [[ "$matching_ready" =~ ^[0-9]+$ ]] || return 1
    (( matching_ready == 15 )) || return 1
    capture_matching_committed_offsets "$offsets_file" || return 1
    critical_ready=true
    now_epoch_ms="$(date +%s%3N)" || return 1
    (( now_epoch_ms <= probe_deadline_epoch_ms )) || return 1
    jq -n \
      --argjson sampleIndex "$sample" \
      --argjson queryPodCount "$query_pod_count" \
      --argjson matchingReady "$matching_ready" \
      --argjson criticalConsumersReady "$critical_ready" \
      --slurpfile consumerState "$state_file" \
      --slurpfile criticalPathHealth "$health_file" \
      --slurpfile matchingCommitted "$offsets_file" \
      '{sampleIndex:$sampleIndex,queryPodCount:$queryPodCount,
        matchingReady:$matchingReady,criticalConsumersReady:$criticalConsumersReady,
        consumerState:$consumerState[0],criticalPathHealth:$criticalPathHealth[0],
        matchingCommittedOffsets:$matchingCommitted[0]}' \
      >>"$samples_file" || return 1
    if (( sample + 1 < probe_seconds )); then
      now_epoch_ms="$(date +%s%3N)" || return 1
      remaining_ms=$((probe_deadline_epoch_ms - now_epoch_ms))
      (( remaining_ms > 0 )) || return 1
      if (( remaining_ms >= 1000 )); then
        sleep 1
      else
        sleep "0.$(printf '%03d' "$remaining_ms")"
      fi
    fi
  done

  local probe_completed_epoch_ms elapsed_milliseconds
  probe_completed_epoch_ms="$(date +%s%3N)" || return 1
  (( probe_completed_epoch_ms <= probe_deadline_epoch_ms )) || return 1
  elapsed_milliseconds=$((probe_completed_epoch_ms - probe_started_epoch_ms))
  jq -n \
    --argjson probeDurationSeconds "$probe_seconds" \
    --argjson probeStartedEpochMs "$probe_started_epoch_ms" \
    --argjson probeCompletedEpochMs "$probe_completed_epoch_ms" \
    --argjson elapsedMilliseconds "$elapsed_milliseconds" \
    --argjson commandTimeoutSeconds "$command_timeout_seconds" \
    --slurpfile samples "$samples_file" \
    '{probeDurationSeconds:$probeDurationSeconds,
      probeStartedEpochMs:$probeStartedEpochMs,probeCompletedEpochMs:$probeCompletedEpochMs,
      elapsedMilliseconds:$elapsedMilliseconds,commandTimeoutSeconds:$commandTimeoutSeconds,
      sampleCount:($samples | length),
      samples:$samples}' >"$destination"
}

capture_consumer_state() {
  local destination="$1"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1

  kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -c "
      SELECT json_build_object(
        'persistenceProgress', COALESCE((
          SELECT json_agg(row_to_json(p) ORDER BY p.partition_id)
          FROM (
            SELECT partition_id, last_processed_offset
            FROM persistence.matching_consumer_progress
            WHERE consumer_name = 'persistence-matching-events'
          ) p
        ), '[]'::json),
        'accountProgress', COALESCE((
          SELECT json_agg(row_to_json(a) ORDER BY a.partition_id)
          FROM (
            SELECT partition_id, last_processed_offset
            FROM account_service.matching_event_consumer_progress
            WHERE consumer_name = 'account-final-matching-events'
          ) a
        ), '[]'::json),
        'quickfixProgress', COALESCE((
          SELECT json_agg(row_to_json(q) ORDER BY q.partition_id)
          FROM (
            SELECT partition_id, last_processed_offset
            FROM quickfix_gateway.matching_consumer_progress
            WHERE consumer_name = 'quickfix-final-matching-events'
          ) q
        ), '[]'::json),
        'persistenceQuarantines', (
          SELECT COUNT(*) FROM persistence.matching_consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'accountQuarantines', (
          SELECT COUNT(*) FROM account_service.matching_event_consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'quickfixQuarantines', (
          SELECT COUNT(*) FROM quickfix_gateway.matching_consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'persistenceQuarantineHistory', (
          SELECT COUNT(*) FROM persistence.matching_consumer_quarantines
        ),
        'accountQuarantineHistory', (
          SELECT COUNT(*) FROM account_service.matching_event_consumer_quarantines
        ),
        'quickfixQuarantineHistory', (
          SELECT COUNT(*) FROM quickfix_gateway.matching_consumer_quarantines
        ),
        'quickfixPendingIntents', (
          SELECT COUNT(*) FROM quickfix_gateway.fix_delivery_intents
          WHERE status = 'PENDING'
        ),
        'admissionStateCounts', COALESCE((
          SELECT json_agg(row_to_json(admission) ORDER BY admission.state)
          FROM (
            SELECT state, COUNT(*) AS count
            FROM risk_service.admission_journal
            GROUP BY state
          ) admission
        ), '[]'::json),
        'riskQuarantines', (
          SELECT COUNT(*) FROM risk_service.consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'accountReservationStateCounts', COALESCE((
          SELECT json_agg(row_to_json(reservation) ORDER BY reservation.status)
          FROM (
            SELECT status, COUNT(*) AS count
            FROM account_service.account_reservations
            GROUP BY status
          ) reservation
        ), '[]'::json),
        'marketDataProgress', COALESCE((
          SELECT json_agg(row_to_json(market_data) ORDER BY market_data.partition_id)
          FROM (
            SELECT partition_id, recovery_state
            FROM market_data_projection.partition_projection_progress
          ) market_data
        ), '[]'::json),
        'marketDataInstrumentCount', (
          SELECT COUNT(*) FROM market_data_projection.instrument_market_data
        ),
        'marketDataDeadLetters', (
          SELECT COUNT(*) FROM market_data_projection.matching_event_dead_letters
        ),
        'activeMatchingOrders', (
          SELECT COUNT(*) FROM persistence.matching_order_projections
          WHERE status IN ('RESTING', 'PARTIALLY_FILLED')
        )
      )::text;
    " | jq -e . >"$destination"
}

capture_query_service_outage_state() {
  local destination="$1"
  kns get pods -l app.kubernetes.io/name=query-service -o json |
    jq '{queryPodCount:(.items | length), queryPodNames:[.items[].metadata.name]}' \
      >"$destination"
  jq -e '.queryPodCount == 0' "$destination" >/dev/null
}

require_clean_baseline() {
  local state="$1"
  jq -e '
    .persistenceQuarantines == 0
    and .accountQuarantines == 0
    and .quickfixQuarantines == 0
    and .persistenceQuarantineHistory == 0
    and .accountQuarantineHistory == 0
    and .quickfixQuarantineHistory == 0
    and .riskQuarantines == 0
    and .quickfixPendingIntents == 0
    and .marketDataDeadLetters == 0
    and .activeMatchingOrders == 0
  ' "$state" >/dev/null ||
    die 'baseline contains quarantine history, pending FIX delivery, or active Matching orders'
}

decode_configmap_file() {
  local configmap="$1"
  local key="$2"
  kns get "configmap/$configmap" -o json |
    jq -r --arg key "$key" '
      if .binaryData[$key] != null then
        .binaryData[$key] | @base64d
      elif .data[$key] != null then
        .data[$key]
      else
        empty
      end
    '
}

configured_quickfix_ingress_venue() {
  local application
  application="$(decode_configmap_file quickfix-gateway-config application.yaml)"
  [[ -n "$application" ]] ||
    die 'quickfix-gateway-config does not contain application.yaml'

  local venue
  venue="$(
    awk '
      /^[[:space:]]+venue-mic:[[:space:]]*/ {
        value = $0
        sub(/^[[:space:]]+venue-mic:[[:space:]]*/, "", value)
        sub(/[[:space:]]+$/, "", value)
        print value
        exit
      }
    ' <<<"$application"
  )"
  [[ "$venue" == "XTAI" || "$venue" == "ROCO" ]] ||
    die 'QuickFIX ingress venue must be explicitly configured as XTAI or ROCO'
  printf '%s\n' "$venue"
}

current_taipei_calendar_day() {
  TZ=Asia/Taipei date +%F
}

expected_fix_trading_day() {
  local requested="${SIMPLEMATCH_CERTIFICATION_TRADING_DAY:-}"
  if [[ -z "$requested" ]]; then
    current_taipei_calendar_day
    return
  fi
  if [[ ! "$requested" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
    die "SIMPLEMATCH_CERTIFICATION_TRADING_DAY must use YYYY-MM-DD: $requested"
    return 1
  fi
  printf '%s\n' "$requested"
}

require_live_fix_trading_day() {
  local configured_trading_day="$1"
  local expected_trading_day
  expected_trading_day="$(expected_fix_trading_day)" || return 1

  if [[ "$configured_trading_day" != "$expected_trading_day" ]]; then
    die "retained namespace trading day $configured_trading_day does not match expected certification trading day $expected_trading_day"
    return 1
  fi
}

select_market_input() {
  if [[ "$(kns get configmap matching-session-config -o jsonpath='{.immutable}')" != "true" ]]; then
    die 'matching-session-config must be immutable'
    return 1
  fi
  artifact_json="$(decode_configmap_file matching-daily-artifact market_reference.json)"
  artifact_checksum="$(decode_configmap_file matching-daily-artifact market_reference.sha256 | tr -d '\r\n')"
  [[ -n "$artifact_json" ]] || die 'matching-daily-artifact does not contain market_reference.json'
  [[ "$artifact_checksum" =~ ^[0-9a-f]{64}$ ]] ||
    die 'matching-daily-artifact does not contain a valid market_reference.sha256'

  trading_day="$(kns get configmap matching-session-config -o jsonpath='{.data.trading_day}')"
  trading_session_id="$(kns get configmap matching-session-config -o jsonpath='{.data.trading_session_id}')"
  matching_image_identity="$(kns get configmap matching-session-config -o jsonpath='{.data.matching_image_digest}')"
  [[ "$trading_day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] ||
    die 'matching-session-config trading_day is invalid'
  [[ -n "$trading_session_id" ]] || die 'matching-session-config trading_session_id is missing'
  [[ -n "$matching_image_identity" ]] || die 'matching-session-config matching_image_digest is missing'
  [[ "$(jq -r '.metadata.tradingDay' <<<"$artifact_json")" == "$trading_day" ]] ||
    die 'Market Reference trading day does not match matching-session-config'
  require_live_fix_trading_day "$trading_day"

  routing_algorithm_version="$(jq -r '.metadata.routingAlgorithmVersion // empty' <<<"$artifact_json")"
  [[ -n "$routing_algorithm_version" ]] || die 'Market Reference routing algorithm version is missing'
  artifact_id="market-reference-$trading_day"
  gateway_venue_mic="$(configured_quickfix_ingress_venue)"

  selected_instrument="$(
    jq -c --arg venue_mic "$gateway_venue_mic" '
      [.marketSnapshot.instruments[]
       | select(.venueMic == $venue_mic)
       | select(.eligibility == "ELIGIBLE")
       | select(.referencePriceUnits != null)]
      | sort_by(.symbol)
      | .[0] // empty
    ' <<<"$artifact_json"
  )"
  [[ -n "$selected_instrument" ]] ||
    die "Market Reference contains no eligible priced instrument for QuickFIX ingress venue $gateway_venue_mic"
  venue_mic="$(jq -r '.venueMic // empty' <<<"$selected_instrument")"
  symbol="$(jq -r '.symbol // empty' <<<"$selected_instrument")"
  price_units="$(jq -r '.referencePriceUnits' <<<"$selected_instrument")"
  rule_id="$(jq -r '.marketRuleId // empty' <<<"$selected_instrument")"
  [[ "$venue_mic" == "$gateway_venue_mic" ]] ||
    die 'selected instrument venue does not match the QuickFIX ingress venue'
  [[ -n "$symbol" ]] || die 'selected symbol is invalid'
  [[ -n "$rule_id" ]] || die 'selected market rule is invalid'

  quantity="$(
    jq -r --arg rule_id "$rule_id" '
      .marketRules.rules[]
      | select(.ruleId == $rule_id)
      | .boardLotShares
    ' <<<"$artifact_json" | head -n 1
  )"
  [[ "$price_units" =~ ^[1-9][0-9]*$ ]] || die 'selected price units are invalid'
  [[ "$quantity" =~ ^[1-9][0-9]*$ ]] || die 'selected board lot quantity is invalid'
  price="$(( price_units / 10000 )).$(printf '%04d' "$(( price_units % 10000 ))")"
  printf '%s\n' "$selected_instrument" | jq . >"$evidence_dir/submission/selected-instrument.json"
}

seed_account_limit() {
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || die 'cannot resolve PostgreSQL Pod for account fixture'
  local now_ms
  now_ms="$(( $(date +%s) * 1000 ))"
  kns exec -i "$postgres" -c postgres -- psql -U simplematch -d simplematch \
    -v ON_ERROR_STOP=1 >"$evidence_dir/submission/account-fixture.log" 2>&1 <<SQL
INSERT INTO account_service.account_limits (
  account_id, scope_type, scope_key, trading_day, currency,
  limit_total_notional, reserved_notional, utilized_notional,
  available_notional, updated_at_unix_ms, version
) VALUES (
  '$account_id', 'ACCOUNT', '*', DATE '$trading_day', 'TWD',
  99999999999999999999.00000000, 0, 0,
  99999999999999999999.00000000, $now_ms, 0
);
SQL
}

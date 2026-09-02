#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
matching_status_module="$script_dir/../../critical-consumers/lib/matching-status.sh"
cluster_data_module="$script_dir/../../critical-consumers/lib/cluster-data.sh"
temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

(
  source "$cluster_data_module"
  context=test-context
  namespace=test-namespace
  unhealthy_resource=""
  large_pod_payload=true

  kns() {
    [[ "$1" == get ]] || return 1
    local target="$2"
    local replicas=1
    local ready_replicas=1
    local selector resource pod_name pod_ready
    if [[ "$target" == pvc ]]; then
      jq -n '{items:[range(0;15) |
        {metadata:{name:("matching-baseline-matching-" + tostring)},
         status:{phase:"Bound"},
         spec:{volumeName:("pv-" + tostring),accessModes:["ReadWriteOncePod"]}}]}'
      return 0
    fi
    if [[ "$target" == pv ]]; then
      jq -n '{items:[range(0;15) |
        {metadata:{name:("pv-" + tostring)},
         spec:{nodeAffinity:{required:{nodeSelectorTerms:[{matchExpressions:[
           {key:"kubernetes.io/hostname",values:["worker-" + tostring]}]}]}}}}]}'
      return 0
    fi
    if [[ "$target" == statefulset/matching ]]; then
      jq -n '{metadata:{name:"matching",uid:"matching-statefulset-uid"},
        spec:{replicas:15},status:{readyReplicas:15,
          currentRevision:"matching-revision",updateRevision:"matching-revision"}}'
      return 0
    fi
    if [[ "$target" == pods ]]; then
      if [[ "$3" == -l ]]; then
        selector="$4"
        pod_name="${selector#*=}"
      else
        pod_name=matching
      fi
      [[ "$pod_name" == matching ]] && replicas=15
      case "$pod_name" in
        matching) resource=statefulset/matching ;;
        quickfix-gateway) resource=statefulset/quickfix-gateway ;;
        *) resource=deployment/$pod_name ;;
      esac
      pod_ready=true
      [[ "$unhealthy_resource" == "$resource" ]] && pod_ready=false
      jq -n \
        --arg name "$pod_name" \
        --argjson count "$replicas" \
        --argjson largePayload "${large_pod_payload:-false}" \
        --argjson ready "$pod_ready" '
          {items:[range(0;$count) |
            {metadata:{name:($name + "-" + tostring),uid:($name + "-uid-" + tostring),
               annotations:(if $largePayload then
                 {contractPadding:("x" * 180000)} else {} end),
               labels:{"app.kubernetes.io/name":$name,
                 "apps.kubernetes.io/pod-index":(if $name == "matching" then tostring else "" end),
                 "controller-revision-hash":(if $name == "matching" then "matching-revision" else "" end)},
               ownerReferences:(if $name == "matching" then
                 [{kind:"StatefulSet",name:"matching",controller:true}] else [] end)},
             spec:{nodeName:("worker-" + tostring),
               volumes:(if $name == "matching" then
                 [{name:"matching-baseline",persistentVolumeClaim:
                   {claimName:("matching-baseline-matching-" + tostring)}}] else [] end)},
             status:{phase:(if $ready then "Running" else "Pending" end),
               conditions:[{type:"Ready",status:(if $ready then "True" else "False" end)}],
               containerStatuses:[{restartCount:0}]}}]}
        '
      return 0
    fi
    if [[ "$target" == statefulset/matching ]]; then
      replicas=15
      ready_replicas=15
    fi
    [[ "$unhealthy_resource" == "$target" ]] && ready_replicas=0
    jq -n \
      --argjson replicas "$replicas" \
      --argjson readyReplicas "$ready_replicas" \
      '{spec:{replicas:$replicas},status:{readyReplicas:$readyReplicas}}'
  }

  capture_critical_path_health "$temporary_directory/collected-health.json"
  jq -e '
    ([.paths[] | {path,resource}] | sort_by(.path)) == [
      {path:"account",resource:"deployment/account-service"},
      {path:"admission",resource:"deployment/risk-service"},
      {path:"marketData",resource:"deployment/market-data-projection"},
      {path:"matching",resource:"statefulset/matching"},
      {path:"persistence",resource:"deployment/persistence"},
      {path:"quickfix",resource:"statefulset/quickfix-gateway"},
      {path:"reservation",resource:"deployment/account-service"}
    ]
    and all(.paths[];
      .desiredReplicas == .readyReplicas
      and .podCount == .desiredReplicas
      and .readyPodCount == .desiredReplicas
      and (.pods | length) == .desiredReplicas
      and all(.pods[]; .phase == "Running" and .ready == true
        and (.uid | length) > 0 and .restartCount == 0)
    )
  ' "$temporary_directory/collected-health.json" >/dev/null
  critical_path_health_is_healthy "$temporary_directory/collected-health.json"

  unhealthy_resource=deployment/risk-service
  capture_critical_path_health "$temporary_directory/unhealthy-health.json"
  if critical_path_health_is_healthy "$temporary_directory/unhealthy-health.json"; then
    printf '%s\n' 'Collector health accepted an unready workload.' >&2
    exit 1
  fi
)

(
  source "$matching_status_module"
  source "$cluster_data_module"
  evidence_dir="$temporary_directory/evidence"
  mkdir -p "$evidence_dir"

  kafka_pod() { printf '%s\n' kafka-0; }
  kns() {
    [[ "$1" == exec ]] || return 1
    for partition in $(seq 0 14); do
      printf 'matching-partition-consumer-%s matching.commands %s %s\n' \
        "$partition" "$partition" "$((20 + partition))"
    done
  }

  capture_consumer_state() {
    printf '%s\n' '{
      "persistenceQuarantines":0,
      "accountQuarantines":0,
      "quickfixQuarantines":0,
      "riskQuarantines":0,
      "quickfixPendingIntents":0,
      "marketDataDeadLetters":0,
      "marketDataProgress":[{"partition_id":0,"recovery_state":"READY"}]
    }' >"$1"
  }
  capture_query_service_outage_state() {
    printf '%s\n' '{"queryPodCount":0,"queryPodNames":[]}' >"$1"
  }
  capture_critical_path_health() {
    jq -n '{paths:[
      {path:"admission",resource:"deployment/risk-service",desiredReplicas:1,readyReplicas:1,podCount:1,readyPodCount:1,
       restartCount:0,pods:[{name:"risk-0",uid:"risk-uid",phase:"Running",ready:true,restartCount:0}]},
      {path:"reservation",resource:"deployment/account-service",desiredReplicas:1,readyReplicas:1,podCount:1,readyPodCount:1,
       restartCount:0,pods:[{name:"account-0",uid:"account-uid",phase:"Running",ready:true,restartCount:0}]},
      {path:"matching",resource:"statefulset/matching",desiredReplicas:15,readyReplicas:15,podCount:15,readyPodCount:15,
       restartCount:0,pods:[range(0;15) |
         {name:("matching-" + tostring),uid:("matching-uid-" + tostring),
          phase:"Running",ready:true,restartCount:0}]},
      {path:"persistence",resource:"deployment/persistence",desiredReplicas:1,readyReplicas:1,podCount:1,readyPodCount:1,
       restartCount:0,pods:[{name:"persistence-0",uid:"persistence-uid",phase:"Running",ready:true,restartCount:0}]},
      {path:"account",resource:"deployment/account-service",desiredReplicas:1,readyReplicas:1,podCount:1,readyPodCount:1,
       restartCount:0,pods:[{name:"account-0",uid:"account-uid",phase:"Running",ready:true,restartCount:0}]},
      {path:"quickfix",resource:"statefulset/quickfix-gateway",desiredReplicas:1,readyReplicas:1,podCount:1,readyPodCount:1,
       restartCount:0,pods:[{name:"quickfix-0",uid:"quickfix-uid",phase:"Running",ready:true,restartCount:0}]},
      {path:"marketData",resource:"deployment/market-data-projection",desiredReplicas:1,readyReplicas:1,podCount:1,readyPodCount:1,
       restartCount:0,pods:[{name:"market-data-0",uid:"market-data-uid",phase:"Running",ready:true,restartCount:0}]}
    ],matchingTopology:{
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
    }}' >"$1"
  }
  matching_ready_replicas() { printf '%s\n' 15; }
  fake_now_file="$temporary_directory/fake-now"
  printf '%s\n' 0 >"$fake_now_file"
  date() {
    if [[ "$1" == +%s%3N ]]; then
      fake_now=$(( $(<"$fake_now_file") + 1500 ))
      printf '%s\n' "$fake_now" >"$fake_now_file"
      printf '%s\n' "$fake_now"
    else
      command date "$@"
    fi
  }
  sleep() {
    fake_now=$(( $(<"$fake_now_file") + 1000 ))
    printf '%s\n' "$fake_now" >"$fake_now_file"
  }

  capture_query_isolation_probe \
    "$evidence_dir/probe.json" 2
  jq -e '
    .probeDurationSeconds == 2
    and .sampleCount == 2
    and .probeStartedEpochMs <= .probeCompletedEpochMs
    and .elapsedMilliseconds == (.probeCompletedEpochMs - .probeStartedEpochMs)
    and .elapsedMilliseconds > (.probeDurationSeconds * 1000)
    and .commandTimeoutSeconds == 5
    and ([.samples[].sampleIndex] == [0, 1])
    and all(.samples[];
      .queryPodCount == 0
      and .matchingReady == 15
      and .criticalConsumersReady == true
      and ([.criticalPathHealth.paths[].path] | sort) ==
        ["account", "admission", "marketData", "matching", "persistence", "quickfix", "reservation"]
      and (.matchingCommittedOffsets.partitions | length) == 15)
  ' "$evidence_dir/probe.json" >/dev/null

  capture_critical_path_health() {
    jq -n '{paths:[{path:"admission",desiredReplicas:1,readyReplicas:0,podCount:1,
      readyPodCount:0,restartCount:0,pods:[]}]}' >"$1"
  }
  if capture_query_isolation_probe "$evidence_dir/unhealthy.json" 1; then
    printf '%s\n' 'Isolation probe accepted an unhealthy critical path.' >&2
    exit 1
  fi

  matching_ready_replicas() { printf '%s\n' 14; }
  if capture_query_isolation_probe "$evidence_dir/failure.json" 1; then
    printf '%s\n' 'Isolation probe accepted fewer than 15 Matching replicas.' >&2
    exit 1
  fi
)

printf 'Query-service isolation probe contract is valid.\n'

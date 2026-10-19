#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/matching-e2e.sh
source "$script_dir/lib/matching-e2e.sh"

pending_commit='{"runtime_state":"READY","pending_inputs":0,"pending_publications":0,"highest_contiguous_completed_offset":16,"next_commit_offset":17}'
acknowledged_commit='{"runtime_state":"READY","pending_inputs":0,"pending_publications":0,"highest_contiguous_completed_offset":16,"next_commit_offset":null}'
incomplete='{"runtime_state":"READY","pending_inputs":0,"pending_publications":0,"highest_contiguous_completed_offset":15,"next_commit_offset":null}'
pending_publications='{"runtime_state":"READY","pending_inputs":0,"pending_publications":1,"highest_contiguous_completed_offset":16,"next_commit_offset":17}'

matching_e2e_runtime_caught_up "$pending_commit" 17
matching_e2e_runtime_caught_up "$acknowledged_commit" 17
if matching_e2e_runtime_caught_up "$incomplete" 17; then
  printf '%s\n' 'Incomplete offset range unexpectedly passed.' >&2
  exit 1
fi
if matching_e2e_runtime_caught_up "$pending_publications" 17; then
  printf '%s\n' 'Pending publications unexpectedly passed.' >&2
  exit 1
fi

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
runtime_evidence="$(jq -cn '[range(0; 15) | {pod:("matching-" + tostring),uid:("uid-" + tostring),node:"worker-a"}]')"
ring_evidence="$(jq -cn '[range(0; 15) | {runtime_state:"READY",input_ring:{capacity:1024,occupancy:0},output_ring:{capacity:2048,occupancy:0}}]')"
valid_report="$fixture_dir/valid.json"
jq -n --argjson rings "$ring_evidence" '
  {status:"PASSED",kafka_e2e_latency_ns:{p50:1,p99:2,"p99_9":3,max:4},
   kafka_e2e_latency_definition:"per-event helper observation",
   ring_occupancy:{before:$rings,after:$rings},loss:0,duplicates:0,
   replay_lag_seconds:1,replacement_seconds:2,command_end_offset:17,
   fault_mode:"pod-delete",
   target:{old_uid:"old",new_uid:"new",old_node:"worker-a",new_node:"worker-a",
           pvc:"matching-baseline-matching-0",pv:"pv-0",old_restart_count:0,new_restart_count:0},
   evidence:{e2e_before:"e2e-before.json",e2e_after:"e2e-after.json"}}' >"$valid_report"
matching_e2e_report_is_valid "$valid_report" "$runtime_evidence"

worker_stop_report="$fixture_dir/worker-stop.json"
jq '.fault_mode = "worker-stop" |
    .worker_stop = {node:"worker-a",container_id:"container-123",node_not_ready_observed:true,same_container_restarted:true} |
    .target.old_uid = "same-pod" |
    .target.new_uid = "same-pod" |
    .target.new_restart_count = 1' "$valid_report" >"$worker_stop_report"
matching_e2e_report_is_valid "$worker_stop_report" "$runtime_evidence"

missing_worker_stop_evidence="$fixture_dir/missing-worker-stop-evidence.json"
jq '.fault_mode = "worker-stop" | del(.worker_stop)' "$valid_report" >"$missing_worker_stop_evidence"
if matching_e2e_report_is_valid "$missing_worker_stop_evidence" "$runtime_evidence"; then
  printf '%s\n' 'Worker-stop report without worker evidence unexpectedly passed.' >&2
  exit 1
fi

e2e_script="$script_dir/run-matching-e2e-certification.sh"
grep -Fq 'helper_node=' "$e2e_script"
grep -Fq 'simplematch.io/node-pool=local-resilience' "$e2e_script"
grep -Fq 'distinct from $old_node' "$e2e_script"
grep -Fq 'actual_helper_node' "$e2e_script"
grep -Fq 'read_runtime_metrics_with_retry' "$e2e_script"

# Drive the real CLI through baseline metrics collection, stopping at the helper
# creation boundary. All external commands are local fixture adapters.
mkdir -p "$fixture_dir/bin"
cat >"$fixture_dir/bin/kind" <<'SH'
#!/usr/bin/env bash
printf '%s\n' simplematch-live
SH
cat >"$fixture_dir/bin/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" != --context ]] || shift 2
[[ "$1" != -n ]] || shift 2
case "$1 $2" in
  'get nodes') printf '%s\n' '{"items":[{"metadata":{"labels":{"node-role.kubernetes.io/control-plane":""}}},{"metadata":{"labels":{"simplematch.io/node-pool":"local-resilience"}}},{"metadata":{"labels":{"simplematch.io/node-pool":"local-resilience"}}},{"metadata":{"labels":{"simplematch.io/node-pool":"local-resilience"}}}]}' ;;
  'get namespace') printf '{}\n' ;;
  'get statefulset') cat "$MATCHING_READER_FIXTURE/statefulset.json" ;;
  'get pods') cat "$MATCHING_READER_FIXTURE/pods.json" ;;
  'get pod') jq --arg name "$3" '.items[] | select(.metadata.name == $name)' "$MATCHING_READER_FIXTURE/pods.json" ;;
  'get configmap')
    if [[ "$3" == matching-session-config ]]; then
      printf '%s\n' '{"data":{"trading_day":"2026-08-27","trading_session_id":"2026-08-27-regular","matching_image_digest":"fixture-image"}}'
    else
      jq -n --arg artifact '{"metadata":{"routingAlgorithmVersion":"fixture"},"routingPolicy":{"assignments":[{"partitionId":0,"venueMic":"XTAI","symbol":"2330"}]}}' \
        '{data:{"market_reference.json":$artifact,"market_reference.sha256":("a" * 64)}}'
    fi ;;
  'get pvc') printf '%s\n' '{"spec":{"volumeName":"fixture-volume"}}' ;;
  'get pv') printf '{}\n' ;;
  'delete pod') exit 0 ;;
  'run '*) exit 73 ;;
  *)
    if [[ "$1" == exec ]]; then
      printf '%s\n' "$*" >>"$MATCHING_READER_FIXTURE/exec.log"
      [[ "${!#}" == "$MATCHING_READER_PATH" ]] || exit 74
      printf '%s\n' '{"runtime_state":"READY","input_ring":{},"output_ring":{}}'
    else
      exit 75
    fi ;;
esac
SH
chmod +x "$fixture_dir/bin/kind" "$fixture_dir/bin/kubectl"
jq -n '{spec:{replicas:15,template:{spec:{containers:[{name:"matching",env:[
  {name:"MATCHING_KAFKA_BROKERS",value:"kafka:9092"},
  {name:"MATCHING_COMMANDS_TOPIC",value:"matching.commands"},
  {name:"MATCHING_EVENTS_TOPIC",value:"matching.events"}]}]}}}}' >"$fixture_dir/statefulset.json"
for configured_path in /var/run/simplematch/matching/runtime-metrics.json \
    /var/lib/simplematch/matching/runtime-metrics.json; do
  jq -n --arg path "$configured_path" '{items:[range(0;15) | . as $n |
    {metadata:{name:("matching-"+($n|tostring)),uid:("uid-"+($n|tostring)),
      labels:{"apps.kubernetes.io/pod-index":($n|tostring)}},
     spec:{nodeName:"worker-a",volumes:[{name:"matching-baseline",persistentVolumeClaim:{claimName:"fixture-claim"}}],
       containers:[{name:"matching",image:"fixture-image",env:[{name:"MATCHING_METRICS_PATH",value:$path}]}]},
     status:{conditions:[{type:"Ready",status:"True"}],containerStatuses:[{name:"matching",restartCount:0}]}}]}' \
    >"$fixture_dir/pods.json"
  : >"$fixture_dir/exec.log"
  result=0
  PATH="$fixture_dir/bin:$PATH" MATCHING_READER_FIXTURE="$fixture_dir" \
    MATCHING_READER_PATH="$configured_path" SIMPLEMATCH_MATCHING_E2E_BIN=/bin/true \
    bash "$e2e_script" --namespace contract --report "$fixture_dir/reader/report.json" \
    >"$fixture_dir/reader.log" 2>&1 || result=$?
  [[ "$result" == 73 && "$(wc -l <"$fixture_dir/exec.log")" == 15 ]] || {
    cat "$fixture_dir/reader.log" >&2
    printf 'Matching CLI did not read all configured runtime paths: %s\n' "$configured_path" >&2
    exit 1
  }
done

for invalid in missing unknown duplicate; do
  jq '.items[].spec.containers[0].env=[{name:"MATCHING_METRICS_PATH",value:"/var/run/simplematch/matching/runtime-metrics.json"}]' \
    "$fixture_dir/pods.json" >"$fixture_dir/valid-pods.json"
  cp "$fixture_dir/valid-pods.json" "$fixture_dir/pods.json"
  case "$invalid" in
    missing) jq '.items[].spec.containers[0].env=[]' "$fixture_dir/pods.json" ;;
    unknown) jq '.items[].spec.containers[0].env[0].value="/tmp/stale.json"' "$fixture_dir/pods.json" ;;
    duplicate) jq '.items[].spec.containers[0].env=[{name:"MATCHING_METRICS_PATH",value:"/var/run/simplematch/matching/runtime-metrics.json"},{name:"MATCHING_METRICS_PATH",value:"/var/run/simplematch/matching/runtime-metrics.json"}]' "$fixture_dir/pods.json" ;;
  esac >"$fixture_dir/invalid-pods.json"
  cp "$fixture_dir/invalid-pods.json" "$fixture_dir/pods.json"
  : >"$fixture_dir/exec.log"
  if PATH="$fixture_dir/bin:$PATH" MATCHING_READER_FIXTURE="$fixture_dir" \
      MATCHING_READER_PATH=/tmp/stale.json SIMPLEMATCH_MATCHING_E2E_BIN=/bin/true \
      bash "$e2e_script" --namespace contract --report "$fixture_dir/reader/report.json" \
      >"$fixture_dir/reader.log" 2>&1; then
    printf 'Matching CLI accepted invalid metrics config: %s\n' "$invalid" >&2
    exit 1
  fi
  [[ ! -s "$fixture_dir/exec.log" ]] || {
    printf 'Invalid Matching config reached exec: %s\n' "$invalid" >&2
    exit 1
  }
done

invalid_node_report="$fixture_dir/invalid-node.json"
jq '.target.new_node = "worker-b"' "$valid_report" >"$invalid_node_report"
if matching_e2e_report_is_valid "$invalid_node_report" "$runtime_evidence"; then
  printf '%s\n' 'Cross-node replacement unexpectedly passed.' >&2
  exit 1
fi

incomplete_fleet_report="$fixture_dir/incomplete-fleet.json"
jq '.ring_occupancy.after = .ring_occupancy.after[0:14]' "$valid_report" >"$incomplete_fleet_report"
if matching_e2e_report_is_valid "$incomplete_fleet_report" "$runtime_evidence"; then
  printf '%s\n' 'Incomplete fleet evidence unexpectedly passed.' >&2
  exit 1
fi

printf '%s\n' 'Matching deployed E2E contract tests passed.'

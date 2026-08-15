#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/matching-e2e.sh
source "$script_dir/lib/matching-e2e.sh"

env_or() {
  local value
  value="$(printenv "$1" 2>/dev/null || true)"
  if [[ -n "$value" ]]; then
    printf '%s' "$value"
  else
    printf '%s' "$2"
  fi
}

generate_run_token() {
  od -An -N4 -tx1 /dev/urandom | tr -d '[:space:]'
}

cluster_name="$(env_or SIMPLEMATCH_KIND_CLUSTER_NAME simplematch-live)"
context="$(env_or SIMPLEMATCH_KUBE_CONTEXT "kind-$cluster_name")"
namespace="$(env_or SIMPLEMATCH_NAMESPACE "")"
statefulset_name="$(env_or SIMPLEMATCH_MATCHING_STATEFULSET matching)"
report_path="$(env_or SIMPLEMATCH_E2E_METRICS_REPORT "$repo_root/out/certification/matching-deployed/e2e-metrics.json")"
evidence_dir="$(dirname -- "$report_path")"
helper_binary="$(env_or SIMPLEMATCH_MATCHING_E2E_BIN "$repo_root/out/build/full-native-dev/simplematch-matching-e2e-certification")"
helper_image="$(env_or SIMPLEMATCH_E2E_HELPER_IMAGE "")"
fault_mode="$(env_or SIMPLEMATCH_E2E_FAULT_MODE pod-delete)"
replacement_timeout_seconds="$(env_or SIMPLEMATCH_E2E_REPLACEMENT_TIMEOUT_SECONDS 120)"
replay_timeout_seconds="$(env_or SIMPLEMATCH_E2E_REPLAY_TIMEOUT_SECONDS 60)"
run_token_override="${SIMPLEMATCH_E2E_RUN_TOKEN:-}"
default_run_token="$(generate_run_token)"
run_token="${run_token_override:-$default_run_token}"
helper_pod="matching-e2e-certifier-$run_token"
helper_path="/tmp/simplematch-matching-e2e-certification"

usage() {
  cat <<'EOF'
Usage: scripts/run-matching-e2e-certification.sh [options]

Options:
  --namespace NAME       Existing local certification namespace.
  --context NAME         Kubernetes context (default: kind-simplematch-live).
  --report PATH          E2E metrics report path.
  --fault-mode MODE      pod-delete or process-crash (default: pod-delete).
  --help                 Show this help.

The command runs only the deployed Matching E2E phase. It does not rebuild the
cluster, rerun bootstrap phases, delete PVCs, or delete the namespace. It uses
one targeted Pod or container fault and cleans up only its own helper Pod.
EOF
}

die() {
  printf 'matching E2E certification: %s\n' "$*" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace)
      [[ $# -ge 2 ]] || die "--namespace requires a value"
      namespace="$2"
      shift 2
      ;;
    --context)
      [[ $# -ge 2 ]] || die "--context requires a value"
      context="$2"
      shift 2
      ;;
    --report)
      [[ $# -ge 2 ]] || die "--report requires a value"
      report_path="$2"
      evidence_dir="$(dirname -- "$report_path")"
      shift 2
      ;;
    --fault-mode)
      [[ $# -ge 2 ]] || die "--fault-mode requires a value"
      fault_mode="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown option: $1"
      ;;
  esac
done

[[ -n "$namespace" ]] || die "--namespace or SIMPLEMATCH_NAMESPACE is required"
[[ "$run_token" =~ ^[0-9a-f]{8}$ ]] || die "run token must be eight lowercase hexadecimal characters"
[[ "$namespace" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || die "namespace is not a valid Kubernetes name"
case "$fault_mode" in
  pod-delete|process-crash) ;;
  *) die "fault mode must be pod-delete or process-crash" ;;
esac

for tool in kind kubectl jq base64 od; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
if [[ "$fault_mode" == process-crash ]]; then
  command -v docker >/dev/null 2>&1 || die "docker is required for process-crash mode"
fi
[[ -x "$helper_binary" ]] || die "E2E helper is not executable: $helper_binary"

mkdir -p "$evidence_dir/ring/before" "$evidence_dir/ring/after" "$evidence_dir/replacement"
if [[ -n "$run_token_override" ]]; then
  for prior_report in "$evidence_dir/e2e-before.json" "$evidence_dir/e2e-after.json"; do
    if [[ -f "$prior_report" ]] &&
      jq -e --arg run_token "$run_token" '.run_token == $run_token' "$prior_report" >/dev/null; then
      die "explicit run token already exists in this evidence directory; use a fresh run-owned directory"
    fi
  done
fi

kube() {
  kubectl --context "$context" "$@"
}

kns() {
  kubectl --context "$context" -n "$namespace" "$@"
}

configmap_value() {
  local configmap="$1"
  local key="$2"
  local document
  document="$(kns get configmap "$configmap" -o json)"
  if jq -e --arg key "$key" '.data[$key] != null' <<<"$document" >/dev/null; then
    jq -r --arg key "$key" '.data[$key]' <<<"$document"
  else
    jq -r --arg key "$key" '.binaryData[$key] // empty' <<<"$document" | base64 --decode
  fi
}

cleanup_helper() {
  kns delete pod "$helper_pod" --ignore-not-found --wait=true >/dev/null 2>&1 || true
}
trap cleanup_helper EXIT

kind get clusters | grep -Fxq "$cluster_name" || die "kind cluster is not available: $cluster_name"
kube get nodes -o json >"$evidence_dir/cluster-nodes.json"
node_count="$(jq '.items | length' "$evidence_dir/cluster-nodes.json")"
worker_count="$(jq '[.items[] | select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience")] | length' "$evidence_dir/cluster-nodes.json")"
control_plane_count="$(jq '[.items[] | select(.metadata.labels["node-role.kubernetes.io/control-plane"] == "")] | length' "$evidence_dir/cluster-nodes.json")"
[[ "$node_count" == 4 && "$worker_count" == 3 && "$control_plane_count" == 1 ]] ||
  die "canonical cluster topology is not one control-plane plus three workers"

kns get namespace "$namespace" >/dev/null 2>&1 || die "namespace does not exist: $namespace"
kns get statefulset "$statefulset_name" -o json >"$evidence_dir/statefulset.json"
kns get pods -l app.kubernetes.io/name=matching -o json >"$evidence_dir/pods-before.json"

trading_day="$(configmap_value matching-session-config trading_day)"
trading_session="$(configmap_value matching-session-config trading_session_id)"
image_digest="$(configmap_value matching-session-config matching_image_digest)"
artifact_payload="$(configmap_value matching-daily-artifact market_reference.json)"
artifact_sha256="$(configmap_value matching-daily-artifact market_reference.sha256 | tr -d '[:space:]')"
routing_version="$(jq -r '.metadata.routingAlgorithmVersion' <<<"$artifact_payload")"
kafka_brokers="$(jq -r '.spec.template.spec.containers[] | select(.name == "matching") | .env[] | select(.name == "MATCHING_KAFKA_BROKERS") | .value' "$evidence_dir/statefulset.json")"
commands_topic="$(jq -r '.spec.template.spec.containers[] | select(.name == "matching") | .env[] | select(.name == "MATCHING_COMMANDS_TOPIC") | .value' "$evidence_dir/statefulset.json")"
events_topic="$(jq -r '.spec.template.spec.containers[] | select(.name == "matching") | .env[] | select(.name == "MATCHING_EVENTS_TOPIC") | .value' "$evidence_dir/statefulset.json")"
target_assignment="$(jq -c '[.routingPolicy.assignments[] | select(.partitionId == 0)] | sort_by(.venueMic, .symbol) | .[0] // empty' <<<"$artifact_payload")"
target_partition="$(jq -r '.partitionId // empty' <<<"$target_assignment")"
instrument_venue="$(jq -r '.venueMic // empty' <<<"$target_assignment")"
instrument_symbol="$(jq -r '.symbol // empty' <<<"$target_assignment")"
[[ "$target_partition" == 0 && -n "$instrument_venue" && -n "$instrument_symbol" ]] ||
  die "routing artifact has no instrument assigned to partition 0"
[[ "$trading_day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ && "$artifact_sha256" =~ ^[0-9a-f]{64}$ ]] ||
  die "Matching session or artifact identity is incomplete"
[[ -n "$routing_version" && -n "$kafka_brokers" && -n "$commands_topic" && -n "$events_topic" ]] ||
  die "Matching Kafka or routing configuration is incomplete"

pod_count="$(jq '.items | length' "$evidence_dir/pods-before.json")"
ready_count="$(jq '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | length' "$evidence_dir/pods-before.json")"
replicas="$(jq -r '.spec.replicas // 0' "$evidence_dir/statefulset.json")"
index_count="$(jq '[.items[] | .metadata.labels["apps.kubernetes.io/pod-index"] // empty] | unique | length' "$evidence_dir/pods-before.json")"
[[ "$replicas" == 15 && "$pod_count" == 15 && "$ready_count" == 15 && "$index_count" == 15 ]] ||
  die "Matching baseline is not 15/15 Ready with unique pod indexes"

target_name="$(jq -r --arg partition "$target_partition" '[.items[] | select(.metadata.labels["apps.kubernetes.io/pod-index"] == $partition)] | if length == 1 then .[0].metadata.name else empty end' "$evidence_dir/pods-before.json")"
[[ -n "$target_name" ]] || die "could not select a unique target for matching partition $target_partition"
target_before="$(jq --arg name "$target_name" '.items[] | select(.metadata.name == $name)' "$evidence_dir/pods-before.json")"
old_uid="$(jq -r '.metadata.uid' <<<"$target_before")"
old_node="$(jq -r '.spec.nodeName' <<<"$target_before")"
old_restart_count="$(jq -r '.status.containerStatuses[]? | select(.name == "matching") | .restartCount // 0' <<<"$target_before")"
old_pvc="$(jq -r '.spec.volumes[] | select(.name == "matching-baseline") | .persistentVolumeClaim.claimName' <<<"$target_before")"
target_image="$(jq -r '.spec.containers[] | select(.name == "matching") | .image' <<<"$target_before")"
[[ -n "$old_uid" && -n "$old_node" && "$old_restart_count" =~ ^[0-9]+$ && -n "$old_pvc" && -n "$target_image" ]] ||
  die "matching-0 baseline identity is incomplete"
if [[ -z "$helper_image" ]]; then
  helper_image="$target_image"
fi

kns get pvc "$old_pvc" -o json >"$evidence_dir/replacement/pvc-before.json"
old_pv="$(jq -r '.spec.volumeName' "$evidence_dir/replacement/pvc-before.json")"
[[ -n "$old_pv" && "$old_pv" != null ]] || die "matching-0 PVC is not bound"
kube get pv "$old_pv" -o json >"$evidence_dir/replacement/pv-before.json"
jq -n \
  --arg pod "$target_name" --arg uid "$old_uid" --arg node "$old_node" \
  --arg pvc "$old_pvc" --arg pv "$old_pv" --arg image "$target_image" \
  --argjson restart_count "$old_restart_count" \
  '{pod:$pod,pod_uid:$uid,node:$node,pvc:$pvc,pv:$pv,image:$image,restart_count:$restart_count}' \
  >"$evidence_dir/replacement/target-before.json"

while IFS= read -r pod; do
  kns exec "$pod" -c matching -- cat /var/lib/simplematch/matching/runtime-metrics.json \
    >"$evidence_dir/ring/before/$pod.json"
  jq -e 'has("input_ring") and has("output_ring") and (.runtime_state | type == "string")' \
    "$evidence_dir/ring/before/$pod.json" >/dev/null ||
    die "invalid runtime metrics from $pod before E2E"
done < <(jq -r '.items[] | .metadata.name' "$evidence_dir/pods-before.json" | sort)

kns delete pod "$helper_pod" --ignore-not-found --wait=true >/dev/null
kns run "$helper_pod" \
  --image="$helper_image" --image-pull-policy=IfNotPresent --restart=Never \
  --command -- sleep 600 >/dev/null
kns wait --for=condition=Ready "pod/$helper_pod" --timeout=120s >/dev/null
base64 "$helper_binary" | kns exec -i "$helper_pod" -- \
  sh -c "base64 -d >$helper_path && chmod 755 $helper_path"

run_helper() {
  local token="$1"
  local output_path="$2"
  kns exec "$helper_pod" -- "$helper_path" \
    "$kafka_brokers" "$commands_topic" "$events_topic" "$trading_day" "$trading_session" \
    "$artifact_sha256" "$routing_version" "$image_digest" "$target_partition" \
    "$instrument_venue" "$instrument_symbol" "$token" "/tmp/e2e-$token.json" \
    >"$output_path"
  jq -e '.status == "PASSED" and .loss == 0 and .duplicates == 0 and
    (.command_end_offset | numbers) and
    (.kafka_e2e_latency_definition | type == "string" and length > 0)' \
    "$output_path" >/dev/null || die "Kafka E2E helper did not pass: $output_path"
}

run_helper "$run_token" "$evidence_dir/e2e-before.json"

fault_started_ms="$(date +%s%3N)"
new_uid=""
new_node=""
new_restart_count=""
replacement_ready_ms=""
replacement_deadline=$(( $(date +%s) + replacement_timeout_seconds ))
if [[ "$fault_mode" == pod-delete ]]; then
  kns delete pod "$target_name" --wait=false >"$evidence_dir/replacement/delete.txt"
  kns wait --for=delete "pod/$target_name" --timeout=60s >/dev/null
else
  node_cluster="$(docker inspect --format '{{ index .Config.Labels "io.x-k8s.kind.cluster" }}' \
    "$old_node" 2>/dev/null || true)"
  [[ "$node_cluster" == "$cluster_name" ]] ||
    die "target node is not a worker in the selected kind cluster: $old_node"
  runtime_containers="$(docker exec "$old_node" crictl ps --name matching -o json 2>/dev/null || true)"
  runtime_container_id="$(jq -r --arg pod "$target_name" --arg namespace "$namespace" --arg uid "$old_uid" '
    [.containers[]? | select(
      .state == "CONTAINER_RUNNING" and
      .metadata.name == "matching" and
      .labels["io.kubernetes.pod.name"] == $pod and
      .labels["io.kubernetes.pod.namespace"] == $namespace and
      .labels["io.kubernetes.pod.uid"] == $uid)]
    | if length == 1 then .[0].id else empty end
  ' <<<"$runtime_containers")"
  [[ -n "$runtime_container_id" ]] ||
    die "could not resolve a unique running Matching container for $target_name"
  {
    printf 'node=%s\ncontainer_id=%s\npod_uid=%s\n' \
      "$old_node" "$runtime_container_id" "$old_uid"
    docker exec "$old_node" crictl stop --timeout=0 "$runtime_container_id"
  } >"$evidence_dir/replacement/process-crash.txt" 2>&1
fi
while [[ "$(date +%s)" -le "$replacement_deadline" ]]; do
  target_after="$(kns get pod "$target_name" -o json 2>/dev/null || true)"
  if [[ -n "$target_after" ]]; then
    candidate_uid="$(jq -r '.metadata.uid // empty' <<<"$target_after")"
    candidate_node="$(jq -r '.spec.nodeName // empty' <<<"$target_after")"
    candidate_restart_count="$(jq -r '.status.containerStatuses[]? | select(.name == "matching") | .restartCount // 0' <<<"$target_after")"
    candidate_ready="$(jq -r 'any(.status.conditions[]?; .type == "Ready" and .status == "True")' <<<"$target_after")"
    replacement_observed=false
    if [[ "$fault_mode" == pod-delete ]]; then
      [[ -n "$candidate_uid" && "$candidate_uid" != "$old_uid" && "$candidate_ready" == true ]] &&
        replacement_observed=true
    else
      [[ -n "$candidate_uid" && "$candidate_uid" == "$old_uid" && \
        "$candidate_restart_count" =~ ^[0-9]+$ && "$candidate_restart_count" -gt "$old_restart_count" && \
        "$candidate_ready" == true ]] && replacement_observed=true
    fi
    if [[ "$replacement_observed" == true ]]; then
      new_uid="$candidate_uid"
      new_node="$candidate_node"
      new_restart_count="$candidate_restart_count"
      replacement_ready_ms="$(date +%s%3N)"
      printf '%s\n' "$target_after" >"$evidence_dir/replacement/pod-after.json"
      break
    fi
  fi
  sleep 2
done
[[ -n "$new_uid" ]] || die "Matching replacement did not become Ready within $replacement_timeout_seconds seconds"
[[ "$new_node" == "$old_node" ]] || die "Matching replacement moved away from its node-local PVC"
[[ "$new_restart_count" =~ ^[0-9]+$ ]] || die "Matching replacement restart evidence is incomplete"

kns get pvc "$old_pvc" -o json >"$evidence_dir/replacement/pvc-after.json"
new_pv="$(jq -r '.spec.volumeName' "$evidence_dir/replacement/pvc-after.json")"
kube get pv "$new_pv" -o json >"$evidence_dir/replacement/pv-after.json"
[[ "$new_pv" == "$old_pv" ]] || die "Matching replacement did not retain its original PV"
jq -n \
  --arg pod "$target_name" --arg uid "$new_uid" --arg node "$new_node" \
  --arg pvc "$old_pvc" --arg pv "$new_pv" --argjson restart_count "$new_restart_count" \
  '{pod:$pod,pod_uid:$uid,node:$node,pvc:$pvc,pv:$pv,restart_count:$restart_count}' \
  >"$evidence_dir/replacement/target-after.json"

replacement_ms=$(( replacement_ready_ms - fault_started_ms ))
after_token="$(generate_run_token)"
while [[ "$after_token" == "$run_token" ]]; do
  after_token="$(generate_run_token)"
done
run_helper "$after_token" "$evidence_dir/e2e-after.json"
command_end_offset="$(jq -r '.command_end_offset' "$evidence_dir/e2e-after.json")"

replay_started_ms="$replacement_ready_ms"
replay_deadline=$(( $(date +%s) + replay_timeout_seconds ))
replay_ready_ms=""
while [[ "$(date +%s)" -le "$replay_deadline" ]]; do
  last_runtime_metrics="$(kns exec "$target_name" -c matching -- cat /var/lib/simplematch/matching/runtime-metrics.json 2>/dev/null || true)"
  if [[ -n "$last_runtime_metrics" ]] &&
    matching_e2e_runtime_caught_up "$last_runtime_metrics" "$command_end_offset"; then
    replay_ready_ms="$(date +%s%3N)"
    printf '%s\n' "$last_runtime_metrics" >"$evidence_dir/replacement/replay-complete-metrics.json"
    break
  fi
  sleep 2
done
[[ -n "$replay_ready_ms" ]] || die "Matching offset catch-up did not reach $command_end_offset within $replay_timeout_seconds seconds"
replay_ms=$(( replay_ready_ms - replay_started_ms ))

while IFS= read -r pod; do
  kns exec "$pod" -c matching -- cat /var/lib/simplematch/matching/runtime-metrics.json \
    >"$evidence_dir/ring/after/$pod.json"
  jq -e 'has("input_ring") and has("output_ring") and (.runtime_state | type == "string")' \
    "$evidence_dir/ring/after/$pod.json" >/dev/null ||
    die "invalid runtime metrics from $pod after E2E"
done < <(kns get pods -l app.kubernetes.io/name=matching -o json | jq -r '.items[] | .metadata.name' | sort)

replacement_seconds="$(jq -n --argjson milliseconds "$replacement_ms" '$milliseconds / 1000')"
replay_lag_seconds="$(jq -n --argjson milliseconds "$replay_ms" '$milliseconds / 1000')"
loss="$(jq -n --argjson before "$(jq '.loss' "$evidence_dir/e2e-before.json")" \
  --argjson after "$(jq '.loss' "$evidence_dir/e2e-after.json")" '[$before,$after] | max')"
duplicates="$(jq -n --argjson before "$(jq '.duplicates' "$evidence_dir/e2e-before.json")" \
  --argjson after "$(jq '.duplicates' "$evidence_dir/e2e-after.json")" '[$before,$after] | max')"
after_latency="$(jq '.kafka_e2e_latency_ns' "$evidence_dir/e2e-after.json")"
after_latency_definition="$(jq -r '.kafka_e2e_latency_definition // empty' "$evidence_dir/e2e-after.json")"
[[ -n "$after_latency_definition" ]] || die "Kafka E2E helper did not report its latency definition"
ring_before="$(jq -s '[.[] | {runtime_state, input_ring, output_ring}]' "$evidence_dir/ring/before"/*.json)"
ring_after="$(jq -s '[.[] | {runtime_state, input_ring, output_ring}]' "$evidence_dir/ring/after"/*.json)"

jq -n \
  --arg generated_at_utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg cluster "$cluster_name" --arg context "$context" --arg namespace "$namespace" \
  --arg fault_mode "$fault_mode" \
  --arg target "$target_name" --arg old_uid "$old_uid" --arg new_uid "$new_uid" \
  --arg old_node "$old_node" --arg new_node "$new_node" --arg pvc "$old_pvc" --arg pv "$old_pv" \
  --argjson old_restart_count "$old_restart_count" --argjson new_restart_count "$new_restart_count" \
  --argjson latency "$after_latency" --arg latency_definition "$after_latency_definition" \
  --argjson before_loss "$(jq '.loss' "$evidence_dir/e2e-before.json")" \
  --argjson after_loss "$(jq '.loss' "$evidence_dir/e2e-after.json")" \
  --argjson before_duplicates "$(jq '.duplicates' "$evidence_dir/e2e-before.json")" \
  --argjson after_duplicates "$(jq '.duplicates' "$evidence_dir/e2e-after.json")" \
  --argjson loss "$loss" --argjson duplicates "$duplicates" \
  --argjson replacement_seconds "$replacement_seconds" --argjson replay_lag_seconds "$replay_lag_seconds" \
  --argjson ring_before "$ring_before" --argjson ring_after "$ring_after" \
  --argjson replacement_limit "$replacement_timeout_seconds" --argjson replay_limit "$replay_timeout_seconds" \
  --arg command_end_offset "$command_end_offset" \
  --arg e2e_before "$(basename -- "$evidence_dir/e2e-before.json")" \
  --arg e2e_after "$(basename -- "$evidence_dir/e2e-after.json")" \
  '(
    ($loss == 0) and ($duplicates == 0) and
    ($replacement_seconds <= $replacement_limit) and
    ($replay_lag_seconds <= $replay_limit)
  ) as $passed |
  {schema_version:1,status:(if $passed then "PASSED" else "FAILED" end),
   generated_at_utc:$generated_at_utc,profile:"full-local-e2e",
   cluster:$cluster,context:$context,namespace:$namespace,
   fault_mode:$fault_mode,
   target:{pod:$target,old_uid:$old_uid,new_uid:$new_uid,old_node:$old_node,new_node:$new_node,
     pvc:$pvc,pv:$pv,old_restart_count:$old_restart_count,new_restart_count:$new_restart_count},
   kafka_e2e_latency_ns:$latency,kafka_e2e_latency_definition:$latency_definition,
   ring_occupancy:{before:$ring_before,after:$ring_after},
   loss:$loss,duplicates:$duplicates,
   replay_lag_seconds:$replay_lag_seconds,replacement_seconds:$replacement_seconds,
   limits:{replay_lag_seconds:$replay_limit,replacement_seconds:$replacement_limit},
   command_end_offset:($command_end_offset|tonumber),
   evidence:{e2e_before:$e2e_before,e2e_after:$e2e_after},
   observed:{before:{loss:$before_loss,duplicates:$before_duplicates},after:{loss:$after_loss,duplicates:$after_duplicates}},
   replay_lag_definition:"time from replacement Ready until runtime metrics show READY, zero pending inputs, zero pending publications, and either a pending commit covering or an acknowledged commit completing the post-replacement command batch",
   claim_boundary:["local deployed Kafka E2E and the selected process/Pod recovery evidence","replay lag is bounded local offset catch-up evidence for the marker batch","not full-day replay, soak, production latency, automatic failover, or cross-node storage HA"],
   failure_reason:(if $passed then null else "one or more local E2E assertions exceeded the accepted bound" end)}' \
  >"$report_path"

cat "$report_path"
printf 'E2E report written to %s\n' "$report_path" >&2

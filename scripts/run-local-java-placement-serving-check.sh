#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

# Focused runtime evidence for the shared Java placement and health contract.
# The command consumes an already deployed, source-aligned disposable namespace;
# it owns only the reversible Redis outage used to exercise liveness boundaries.

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$script_dir/lib/local-certification-provenance.sh"
# shellcheck source=scripts/lib/local-java-placement-serving.sh
source "$script_dir/lib/local-java-placement-serving.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="${SIMPLEMATCH_KUBE_CONTEXT:-kind-$cluster_name}"
namespace="${SIMPLEMATCH_JAVA_PLACEMENT_NAMESPACE:-}"
namespace_run_id="${SIMPLEMATCH_JAVA_PLACEMENT_NAMESPACE_RUN_ID:-}"
retained_evidence_dir="${SIMPLEMATCH_JAVA_PLACEMENT_RETAINED_EVIDENCE_DIR:-}"
evidence_dir="${SIMPLEMATCH_JAVA_PLACEMENT_EVIDENCE_DIR:-}"
timeout_seconds="${SIMPLEMATCH_JAVA_PLACEMENT_TIMEOUT_SECONDS:-300}"
observe_seconds="${SIMPLEMATCH_JAVA_PLACEMENT_OBSERVE_SECONDS:-5}"
preflight_timeout_seconds=60
control_plane_window_seconds=5
cleanup_timeout_seconds=30

deadline_epoch=0
cleanup_deadline_epoch=0
failure_reason=""
current_stage=preflight
evidence_initialized=false
redis_scaled=false
report_published=false
port_forward_pid=""
port_forward_port=""
original_redis_replicas=""
target_pod=""
target_pod_uid=""
target_node=""
target_restart_count=""
outage_observed_seconds=0

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/run-local-java-placement-serving-check.sh \
    --namespace NAME \
    --namespace-run-id RUN_ID \
    --retained-evidence-dir PATH \
    --evidence-dir PATH \
    [--timeout-seconds N] [--observe-seconds N]

The observer consumes a source-aligned, lifecycle-labelled disposable namespace
that already serves the five Java workloads. It records the representative
query-service placement, startup/readiness/liveness probes, and application
serving response. It then scales only Redis to zero for a bounded outage,
proves query-service remains serving without a Pod restart, restores Redis, and
writes diagnostic-only evidence. It never applies manifests or claims a
full-local aggregate certification PASS.
EOF_USAGE
}

die() {
  failure_reason="$*"
  printf 'Java placement/serving observer: %s\n' "$failure_reason" >&2
  exit 1
}

remaining_seconds() {
  local remaining=$((deadline_epoch - SECONDS))
  (( remaining > 0 )) || return 124
  printf '%s\n' "$remaining"
}

run_bounded() {
  local remaining command_name="${1:-command}"
  remaining="$(remaining_seconds)" || {
    failure_reason="observer deadline elapsed before $command_name"
    return 124
  }
  timeout --foreground "${remaining}s" "$@"
}

run_cleanup_bounded() {
  local remaining
  if (( cleanup_deadline_epoch == 0 )); then
    cleanup_deadline_epoch=$((SECONDS + cleanup_timeout_seconds))
  fi
  remaining=$((cleanup_deadline_epoch - SECONDS))
  (( remaining > 0 )) || return 124
  timeout --foreground "${remaining}s" "$@"
}

kube() {
  run_bounded "$SIMPLEMATCH_KIND_KUBECTL_BIN" --context "$context" "$@"
}

kns() {
  run_bounded "$SIMPLEMATCH_KIND_KUBECTL_BIN" --context "$context" -n "$namespace" "$@"
}

cleanup_kube() {
  run_cleanup_bounded "$SIMPLEMATCH_KIND_KUBECTL_BIN" --context "$context" -n "$namespace" "$@"
}

stop_port_forward() {
  local pid="${port_forward_pid:-}"
  [[ -n "$pid" ]] || return 0
  kill "$pid" >/dev/null 2>&1 || true
  wait "$pid" >/dev/null 2>&1 || true
  port_forward_pid=""
  port_forward_port=""
}

restore_redis() {
  local replicas="$1"
  local remaining

  [[ -n "$replicas" ]] || return 1
  remaining=$((cleanup_deadline_epoch - SECONDS))
  (( remaining > 0 )) || return 1
  cleanup_kube scale deployment/redis "--replicas=$replicas" >/dev/null || return 1
  remaining=$((cleanup_deadline_epoch - SECONDS))
  (( remaining > 0 )) || return 1
  cleanup_kube rollout status deployment/redis "--timeout=${remaining}s" >/dev/null || return 1
  redis_scaled=false
}

write_failure_report() {
  local exit_status="$1"
  [[ "$evidence_initialized" == true ]] || return 0
  jq -n \
    --arg profile "$JAVA_PLACEMENT_SERVING_PROFILE" \
    --arg cluster "$cluster_name" \
    --arg context "$context" \
    --arg namespace "$namespace" \
    --arg namespace_run_id "$namespace_run_id" \
    --arg stage "$current_stage" \
    --arg reason "${failure_reason:-observer did not complete}" \
    --argjson exit_status "$exit_status" \
    '{schema_version:1,profile:$profile,status:"FAIL",cluster:$cluster,
      context:$context,namespace:$namespace,namespace_run_id:$namespace_run_id,
      stage:$stage,exit_status:$exit_status,failure_reason:$reason,
      claim_boundary:["focused local Java placement and serving diagnostic"]}' \
    >"$evidence_dir/java-placement-serving.json"
}

cleanup() {
  local status="$1"
  local cleanup_status=0
  trap - ERR EXIT INT TERM
  set +e
  cleanup_deadline_epoch=$((SECONDS + cleanup_timeout_seconds))
  stop_port_forward
  if [[ "$redis_scaled" == true && -n "$original_redis_replicas" ]]; then
    restore_redis "$original_redis_replicas" || cleanup_status=1
  fi
  if (( cleanup_status != 0 )); then
    status=1
    [[ -n "$failure_reason" ]] ||
      failure_reason='Redis restoration failed during observer cleanup'
  fi
  if [[ "$evidence_initialized" == true ]]; then
    run_cleanup_bounded docker system df >"$evidence_dir/docker-system-df-after.txt" ||
      {
        cleanup_status=1
        status=1
        [[ -n "$failure_reason" ]] ||
          failure_reason='Docker resource inventory could not be captured after observation'
      }
  fi
  if [[ "$report_published" != true || "$status" -ne 0 ]]; then
    write_failure_report "$status"
  fi
  set -e
  exit "$status"
}
trap 'cleanup $?' EXIT
trap 'failure_reason="observer interrupted during $current_stage"; exit 130' INT TERM

prepare_evidence_dir() {
  local existing
  [[ -n "$evidence_dir" ]] ||
    evidence_dir="$repo_root/out/resilience/java-placement-serving-$(date -u +%Y%m%d-%H%M%S)-$$"
  if [[ -e "$evidence_dir" ]]; then
    existing="$(find "$evidence_dir" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null || true)"
    [[ -z "$existing" ]] || die "evidence directory must be empty: $evidence_dir"
  fi
  mkdir -p "$evidence_dir/placement" "$evidence_dir/health"
  evidence_dir="$(cd -- "$evidence_dir" && pwd)"
  evidence_initialized=true
}

print_placement_summary() {
  local stage="$1"
  local summary_file="$evidence_dir/placement/${stage}.json"
  local summary

  summary="$(jq -r --arg stage "$stage" '
    "\($stage): pods=\(.pod_count)/\(.desired_replicas) "
    + "ready=\(.ready_pod_count) nodes=\(.distinct_nodes) "
    + "endpoints=\(.ready_endpoint_count) startup=\(.startup_completed_pod_count) "
    + "restarts=\([.pods[].restart_count] | join(","))"
  ' "$summary_file")" || die "cannot summarize query-service placement during $stage"
  printf 'Java placement observer %s\n' "$summary"
}

print_health_summary() {
  local stage="$1"
  local health_file="$evidence_dir/health/${stage}.json"
  local summary

  summary="$(jq -r --arg stage "$stage" '
    "\($stage): readiness=\(.readiness.http_status)/\(.readiness.body_status) "
    + "liveness=\(.liveness.http_status)/\(.liveness.body_status) "
    + "serving=\(.serving.http_status)/\(.serving.body_status)"
  ' "$health_file")" || die "cannot summarize query-service health during $stage"
  printf 'Java health observer %s\n' "$summary"
}

print_redis_summary() {
  local stage="$1"
  local snapshot_file="$evidence_dir/placement/redis-${stage}.json"
  local summary

  summary="$(jq -r --arg stage "$stage" '
    "\($stage): desired=\(.desired_replicas) ready=\(.ready_replicas) "
    + "ready_pods=\(.ready_pod_count)"
  ' "$snapshot_file")" || die "cannot summarize Redis state during $stage"
  printf 'Redis observer %s\n' "$summary"
}

validate_namespace_and_provenance() {
  local namespace_json labels_run_id labels_manager source_revision retained_source retained_run_id

  [[ -n "$namespace" ]] || die '--namespace is required'
  [[ -n "$namespace_run_id" ]] || die '--namespace-run-id is required'
  [[ "$namespace_run_id" =~ ^[A-Za-z0-9._-]+$ ]] ||
    die '--namespace-run-id contains unsupported characters'
  [[ -n "$retained_evidence_dir" ]] || die '--retained-evidence-dir is required'
  [[ -d "$retained_evidence_dir" ]] ||
    die "retained evidence directory does not exist: $retained_evidence_dir"

  namespace_json="$(kube get namespace "$namespace" -o json)" ||
    die "namespace does not exist: $namespace"
  jq -e --arg run_id "$namespace_run_id" '
    .metadata.labels["simplematch.io/lifecycle"] == "disposable"
    and (.metadata.labels["simplematch.io/managed-by"] == "local-resilience"
      or .metadata.labels["simplematch.io/managed-by"] == "local-production-like-certification")
    and .metadata.labels["simplematch.io/run-id"] == $run_id
  ' <<<"$namespace_json" >/dev/null ||
    die "namespace is not an owned disposable run: $namespace"
  labels_run_id="$(jq -r '.metadata.labels["simplematch.io/run-id"]' <<<"$namespace_json")"
  labels_manager="$(jq -r '.metadata.labels["simplematch.io/managed-by"]' <<<"$namespace_json")"

  source_revision="$(simplematch_certification_source_revision "$repo_root")" ||
    die 'current runtime source is not clean for source-aligned observation'
  retained_source="$(tr -d '\r\n' <"$retained_evidence_dir/source-revision" 2>/dev/null || true)"
  [[ "$retained_source" == "$source_revision" ]] ||
    die "retained source revision ${retained_source:-<missing>} does not match $source_revision"
  retained_run_id="$(awk -F= '$1 == "run_id" {print substr($0, index($0, "=") + 1)}' \
    "$retained_evidence_dir/run-context" 2>/dev/null || true)"
  [[ "$retained_run_id" == "$namespace_run_id" ]] ||
    die "retained run-id ${retained_run_id:-<missing>} does not match namespace run-id $namespace_run_id"
  simplematch_certification_verifier_image \
    "$repo_root" "$namespace" "$retained_evidence_dir" >/dev/null ||
    die 'retained production-like provenance or verifier identity is invalid'

  jq -n \
    --arg namespace "$namespace" \
    --arg run_id "$labels_run_id" \
    --arg manager "$labels_manager" \
    --arg source_revision "$source_revision" \
    '{namespace:$namespace,namespace_run_id:$run_id,managed_by:$manager,
      source_revision:$source_revision}' >"$evidence_dir/provenance.json"
}

validate_tools_and_cluster() {
  local tool clusters nodes_json worker_count ready_workers control_plane_count
  local worker_index worker_json worker_name ready_state
  for tool in jq curl date find grep sed tail sleep timeout sha256sum tr awk docker; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
  done
  command -v "$SIMPLEMATCH_KIND_KUBECTL_BIN" >/dev/null 2>&1 ||
    die "$SIMPLEMATCH_KIND_KUBECTL_BIN is required"
  command -v "$SIMPLEMATCH_KIND_BIN" >/dev/null 2>&1 ||
    die "$SIMPLEMATCH_KIND_BIN is required"
  [[ "$cluster_name" == simplematch-live && "$context" == kind-simplematch-live ]] ||
    die 'observer requires the canonical simplematch-live/kind-simplematch-live target'
  run_bounded docker info >/dev/null 2>&1 || die 'Docker daemon is not reachable'
  [[ "$("$SIMPLEMATCH_KIND_KUBECTL_BIN" config current-context 2>/dev/null || true)" == "$context" ]] ||
    die "current Kubernetes context must be $context"
  clusters="$(run_bounded "$SIMPLEMATCH_KIND_BIN" get clusters)" ||
    die "canonical kind cluster is not available: $cluster_name"
  grep -Fxq "$cluster_name" <<<"$clusters" ||
    die "canonical kind cluster is not available: $cluster_name"
  nodes_json="$(kube get nodes -o json)" ||
    die 'could not read canonical kind nodes'
  worker_count="$(jq '[.items[] | select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience")] | length' <<<"$nodes_json")" ||
    die 'canonical worker JSON is invalid'
  ready_workers=0
  for ((worker_index = 0; worker_index < worker_count; worker_index++)); do
    worker_json="$(jq -c --argjson index "$worker_index" '
      [.items[] | select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience")][$index]
    ' <<<"$nodes_json")" || die 'canonical worker JSON is invalid'
    worker_name="$(jq -er '.metadata.name // error("node name is missing")' <<<"$worker_json")" ||
      die 'canonical worker JSON is missing a node name'
    ready_state="$(simplematch_kind_node_readiness_state "$worker_json")" ||
      die "canonical worker readiness JSON is invalid: $worker_name"
    [[ "$ready_state" == true ]] && ((ready_workers += 1))
  done
  control_plane_count="$(jq '[.items[] | select(.metadata.labels["node-role.kubernetes.io/control-plane"] == "")] | length' <<<"$nodes_json")" ||
    die 'canonical control-plane JSON is invalid'
  [[ "$(jq '.items | length' <<<"$nodes_json")" == 4 &&
    "$worker_count" == 3 && "$ready_workers" == 3 && "$control_plane_count" == 1 ]] ||
    die 'canonical topology is not one control plane plus three Ready workers'
  run_bounded docker system df >"$evidence_dir/docker-system-df-before.txt" ||
    die 'Docker resource inventory could not be captured'
  simplematch_kind_validate_control_plane_stability \
    "$context" "$control_plane_window_seconds" "$preflight_timeout_seconds" \
    "$evidence_dir/control-plane" "$preflight_timeout_seconds" ||
    die 'canonical kind control plane is not stable before the runtime mutation'
}

capture_placement() {
  local stage="$1"
  local deployment_file="$evidence_dir/placement/${stage}-deployment.json"
  local pods_file="$evidence_dir/placement/${stage}-pods.json"
  local nodes_file="$evidence_dir/placement/${stage}-nodes.json"
  local endpoint_slices_file="$evidence_dir/placement/${stage}-endpointslices.json"
  local service_file="$evidence_dir/placement/service.json"
  local summary_file="$evidence_dir/placement/${stage}.json"

  kns get deployment "$JAVA_PLACEMENT_SERVING_SERVICE" -o json >"$deployment_file" ||
    die "cannot capture query-service Deployment during $stage"
  local pod_selector="app.kubernetes.io/name=${JAVA_PLACEMENT_SERVING_SERVICE},app.kubernetes.io/component=java-service"

  kns get pods -l "$pod_selector" -o json >"$pods_file" ||
    die "cannot capture query-service Pods during $stage"
  kube get nodes -o json >"$nodes_file" || die "cannot capture kind nodes during $stage"
  kns get endpointslice -l \
    kubernetes.io/service-name="$JAVA_PLACEMENT_SERVING_SERVICE" -o json \
    >"$endpoint_slices_file" || die "cannot capture query-service EndpointSlices during $stage"
  if [[ ! -s "$service_file" ]]; then
    kns get service "$JAVA_PLACEMENT_SERVING_SERVICE" -o json >"$service_file" ||
      die 'cannot capture query-service Service'
    jq -e --arg service "$JAVA_PLACEMENT_SERVING_SERVICE" \
      --argjson port "$JAVA_PLACEMENT_SERVING_PORT" '
      .spec.selector == {
        "app.kubernetes.io/name":$service,
        "app.kubernetes.io/component":"java-service"
      }
      and any(.spec.ports[]?; .name == "http" and .port == $port and .targetPort == "http")
    ' "$service_file" >/dev/null || die 'query-service Service selector or port is invalid'
  fi
  java_placement_serving_probe_contract_is_valid "$deployment_file" ||
    die "query-service probe wiring is invalid during $stage"
  java_placement_serving_runtime_snapshot \
    "$deployment_file" "$pods_file" "$nodes_file" "$endpoint_slices_file" >"$summary_file" ||
    die "query-service placement snapshot could not be normalized during $stage"
  java_placement_serving_snapshot_file_is_ready "$summary_file" ||
    die "query-service placement is not two Ready, spread Pods during $stage"
  print_placement_summary "$stage"
}

start_port_forward() {
  local log_file="$evidence_dir/port-forward.log"
  local output

  timeout --foreground "$(remaining_seconds)s" "$SIMPLEMATCH_KIND_KUBECTL_BIN" --context "$context" -n "$namespace" \
    port-forward --address 127.0.0.1 "pod/$target_pod" ":$JAVA_PLACEMENT_SERVING_PORT" \
    >"$log_file" 2>&1 &
  port_forward_pid="$!"
  while :; do
    remaining_seconds >/dev/null || die 'query-service port-forward did not start before deadline'
    kill -0 "$port_forward_pid" >/dev/null 2>&1 || {
      cat "$log_file" >&2
      die 'query-service port-forward exited before becoming ready'
    }
    output="$(sed -nE 's/.*127\.0\.0\.1:([0-9]+) -> [0-9]+.*/\1/p' \
      "$log_file" | tail -n 1)"
    if [[ "$output" =~ ^[0-9]+$ ]]; then
      port_forward_port="$output"
      return 0
    fi
    run_bounded sleep 1 || die 'query-service port-forward startup exceeded deadline'
  done
}

capture_http_probe() {
  local stage="$1"
  local name="$2"
  local path="$3"
  local body_file="$evidence_dir/health/${stage}-${name}.json"
  local relative_file="health/${stage}-${name}.json"
  local status body_status body_type digest

  status="$(curl --connect-timeout 2 \
    --max-time "$(remaining_seconds)" -sS -o "$body_file" -w '%{http_code}' \
    "http://127.0.0.1:${port_forward_port}${path}")" ||
    die "$stage $name request failed"
  [[ "$status" == 200 ]] || die "$stage $name returned HTTP $status"
  body_type="$(jq -r 'type' "$body_file")" || die "$stage $name response is not JSON"
  if [[ "$name" == readiness || "$name" == liveness ]]; then
    jq -e '.status == "UP"' "$body_file" >/dev/null ||
      die "$stage $name response did not report UP"
    body_status=UP
  else
    [[ "$body_type" == object ]] || die "$stage serving response is not an object"
    body_status=SERVING
  fi
  digest="$(java_placement_serving_sha256_digest "$body_file")" ||
    die "$stage $name response digest is invalid"
  jq -n \
    --arg path "$path" --arg file "$relative_file" --arg status "$status" \
    --arg body_status "$body_status" --arg body_type "$body_type" \
    --arg digest "$digest" \
    '{path:$path,http_status:($status|tonumber),body_status:$body_status,
      body_type:$body_type,body_file:$file,body_sha256:$digest}'
}

capture_health() {
  local stage="$1"
  local output_file="$evidence_dir/health/${stage}.json"
  local readiness liveness serving

  readiness="$(capture_http_probe "$stage" readiness \
    "$JAVA_PLACEMENT_SERVING_READINESS_PATH")"
  liveness="$(capture_http_probe "$stage" liveness \
    "$JAVA_PLACEMENT_SERVING_LIVENESS_PATH")"
  serving="$(capture_http_probe "$stage" serving \
    "$JAVA_PLACEMENT_SERVING_APPLICATION_PATH")"
  jq -n --argjson readiness "$readiness" --argjson liveness "$liveness" \
    --argjson serving "$serving" \
    '{readiness:$readiness,liveness:$liveness,serving:$serving}' >"$output_file"
  print_health_summary "$stage"
}

redis_replicas() {
  local deployment_file="$1"
  jq -er '.spec.replicas | numbers' "$deployment_file"
}

wait_for_redis_replicas() {
  local expected="$1"
  local deployment_json redis_pods_json ready replicas
  while :; do
    remaining_seconds >/dev/null || return 1
    deployment_json="$(kns get deployment redis -o json 2>/dev/null || true)"
    replicas="$(jq -r '.spec.replicas // -1' <<<"$deployment_json" 2>/dev/null || true)"
    ready="$(jq -r '.status.readyReplicas // 0' <<<"$deployment_json" 2>/dev/null || true)"
    if [[ "$replicas" == "$expected" && "$ready" == "$expected" ]]; then
      if [[ "$expected" == 0 ]]; then
        redis_pods_json="$(kns get pods -l app.kubernetes.io/name=redis -o json)" || return 1
        if ! jq -e 'any(.items[]?;
            any(.status.conditions[]?; .type == "Ready" and .status == "True"))' \
            <<<"$redis_pods_json" >/dev/null; then
          return 0
        fi
      else
        return 0
      fi
    fi
    run_bounded sleep 2 || return 1
  done
}

capture_redis_snapshot() {
  local stage="$1"
  local expected_replicas="$2"
  local deployment_file="$evidence_dir/placement/redis-${stage}-deployment.json"
  local pods_file="$evidence_dir/placement/redis-${stage}-pods.json"
  local snapshot_file="$evidence_dir/placement/redis-${stage}.json"

  kns get deployment redis -o json >"$deployment_file" ||
    die "cannot capture Redis Deployment during $stage"
  kns get pods -l app.kubernetes.io/name=redis -o json >"$pods_file" ||
    die "cannot capture Redis Pods during $stage"
  jq -n \
    --slurpfile deployment "$deployment_file" \
    --slurpfile pods "$pods_file" \
    --arg stage "$stage" '
      ($deployment[0]) as $deploymentObject
      | ($pods[0].items // []) as $podItems
      | {
          stage: $stage,
          deployment_name: ($deploymentObject.metadata.name // ""),
          deployment_uid: ($deploymentObject.metadata.uid // ""),
          desired_replicas: ($deploymentObject.spec.replicas // 0),
          ready_replicas: ($deploymentObject.status.readyReplicas // 0),
          available_replicas: ($deploymentObject.status.availableReplicas // 0),
          updated_replicas: ($deploymentObject.status.updatedReplicas // 0),
          ready_pod_count: (
            $podItems
            | map(select(any(.status.conditions[]?;
                .type == "Ready" and .status == "True")))
            | length
          ),
          pods: ([
            $podItems[]?
            | ([.status.containerStatuses[]?] | if length == 1 then .[0] else {} end) as $containerStatus
            | {
                name: (.metadata.name // ""),
                uid: (.metadata.uid // ""),
                node: (.spec.nodeName // ""),
                phase: (.status.phase // ""),
                ready: any(.status.conditions[]?;
                  .type == "Ready" and .status == "True"),
                restart_count: ($containerStatus.restartCount // -1),
                image_id: ($containerStatus.imageID // "")
              }
          ] | sort_by(.name))
        }
    ' >"$snapshot_file" ||
    die "cannot normalize Redis snapshot during $stage"
  java_placement_serving_redis_snapshot_is_expected \
    "$snapshot_file" "$expected_replicas" ||
    die "Redis snapshot is not the expected $expected_replicas-replica state during $stage"
  print_redis_summary "$stage"
}

assert_target_identity_unchanged() {
  local stage="$1"
  local summary_file="$evidence_dir/placement/${stage}.json"
  local stage_uid stage_restart stage_node
  stage_uid="$(jq -er --arg pod "$target_pod" \
    '.pods[] | select(.name == $pod) | .uid' "$summary_file")" ||
    die "$stage lost target Pod $target_pod"
  stage_restart="$(jq -er --arg pod "$target_pod" \
    '.pods[] | select(.name == $pod) | .restart_count' "$summary_file")" ||
    die "$stage lost target restart count"
  stage_node="$(jq -er --arg pod "$target_pod" \
    '.pods[] | select(.name == $pod) | .node' "$summary_file")" ||
    die "$stage lost target node"
  [[ "$stage_uid" == "$target_pod_uid" ]] ||
    die "$stage replaced target Pod $target_pod"
  [[ "$stage_restart" == "$target_restart_count" ]] ||
    die "$stage increased target restart count"
  [[ "$stage_node" == "$target_node" ]] ||
    die "$stage moved target Pod $target_pod"
}

write_pass_report() {
  local report_file="$evidence_dir/java-placement-serving.json"
  local baseline_file="$evidence_dir/placement/baseline.json"
  local outage_file="$evidence_dir/placement/redis-outage.json"
  local restored_file="$evidence_dir/placement/restored.json"
  local redis_before_file="$evidence_dir/placement/redis-before.json"
  local redis_during_file="$evidence_dir/placement/redis-during.json"
  local redis_after_file="$evidence_dir/placement/redis-after.json"
  local baseline_uid_set outage_uid_set restored_uid_set
  local baseline_restart_set outage_restart_set restored_restart_set
  local baseline_digest outage_digest restored_digest
  local redis_before_digest redis_during_digest redis_after_digest
  local redis_during_replicas redis_after_replicas
  local baseline_placement_file=placement/baseline.json
  local outage_placement_file=placement/redis-outage.json
  local restored_placement_file=placement/restored.json
  local redis_before_report_file=placement/redis-before.json
  local redis_during_report_file=placement/redis-during.json
  local redis_after_report_file=placement/redis-after.json

  baseline_uid_set="$(jq -c '[.pods[] | {name,uid}] | sort_by(.name)' "$baseline_file")"
  outage_uid_set="$(jq -c '[.pods[] | {name,uid}] | sort_by(.name)' "$outage_file")"
  restored_uid_set="$(jq -c '[.pods[] | {name,uid}] | sort_by(.name)' "$restored_file")"
  baseline_restart_set="$(jq -c '[.pods[] | {name,restart_count}] | sort_by(.name)' "$baseline_file")"
  outage_restart_set="$(jq -c '[.pods[] | {name,restart_count}] | sort_by(.name)' "$outage_file")"
  restored_restart_set="$(jq -c '[.pods[] | {name,restart_count}] | sort_by(.name)' "$restored_file")"
  baseline_digest="$(java_placement_serving_sha256_digest "$baseline_file")" ||
    die 'baseline placement digest is invalid'
  outage_digest="$(java_placement_serving_sha256_digest "$outage_file")" ||
    die 'outage placement digest is invalid'
  restored_digest="$(java_placement_serving_sha256_digest "$restored_file")" ||
    die 'restored placement digest is invalid'
  redis_before_digest="$(java_placement_serving_sha256_digest "$redis_before_file")" ||
    die 'baseline Redis digest is invalid'
  redis_during_digest="$(java_placement_serving_sha256_digest "$redis_during_file")" ||
    die 'outage Redis digest is invalid'
  redis_after_digest="$(java_placement_serving_sha256_digest "$redis_after_file")" ||
    die 'restored Redis digest is invalid'
  redis_during_replicas="$(jq -er '.desired_replicas' "$redis_during_file")" ||
    die 'outage Redis replica count is invalid'
  redis_after_replicas="$(jq -er '.desired_replicas' "$redis_after_file")" ||
    die 'restored Redis replica count is invalid'

  jq -n \
    --arg profile "$JAVA_PLACEMENT_SERVING_PROFILE" \
    --arg cluster "$cluster_name" --arg context "$context" \
    --arg namespace "$namespace" --arg namespace_run_id "$namespace_run_id" \
    --arg source_revision "$(jq -r '.source_revision' "$evidence_dir/provenance.json")" \
    --arg service "$JAVA_PLACEMENT_SERVING_SERVICE" \
    --arg pod "$target_pod" --arg pod_uid "$target_pod_uid" --arg node "$target_node" \
    --arg container "$JAVA_PLACEMENT_SERVING_CONTAINER" \
    --arg node_pool "$JAVA_PLACEMENT_SERVING_NODE_POOL" \
    --argjson port "$JAVA_PLACEMENT_SERVING_PORT" \
    --arg startup_path "$JAVA_PLACEMENT_SERVING_STARTUP_PATH" \
    --arg readiness_path "$JAVA_PLACEMENT_SERVING_READINESS_PATH" \
    --arg liveness_path "$JAVA_PLACEMENT_SERVING_LIVENESS_PATH" \
    --argjson replicas "$JAVA_PLACEMENT_SERVING_REPLICAS" \
    --arg redis_before_file "$redis_before_report_file" \
    --arg redis_during_file "$redis_during_report_file" \
    --arg redis_after_file "$redis_after_report_file" \
    --arg redis_before_digest "$redis_before_digest" \
    --arg redis_during_digest "$redis_during_digest" \
    --arg redis_after_digest "$redis_after_digest" \
    --argjson placement "$(jq -n \
      --slurpfile baseline "$baseline_file" \
      --argjson uid_unchanged "$([[ "$baseline_uid_set" == "$outage_uid_set" && "$baseline_uid_set" == "$restored_uid_set" ]] && echo true || echo false)" \
      --argjson restart_unchanged "$([[ "$baseline_restart_set" == "$outage_restart_set" && "$baseline_restart_set" == "$restored_restart_set" ]] && echo true || echo false)" \
      --arg baseline_file "$baseline_placement_file" \
      --arg outage_file "$outage_placement_file" \
      --arg restored_file "$restored_placement_file" \
      --arg baseline_digest "$baseline_digest" \
      --arg outage_digest "$outage_digest" \
      --arg restored_digest "$restored_digest" \
      '{pod_count:($baseline[0].pods|length),ready_pod_count:($baseline[0].pods|map(select(.ready==true))|length),
        node_pool:$node_pool,
        distinct_nodes:$baseline[0].distinct_nodes,ready_endpoint_count:$baseline[0].ready_endpoint_count,
        startup_completed_pod_count:$baseline[0].startup_completed_pod_count,
        image_ids:($baseline[0].pods|map(.image_id)|unique),
        pod_uid_unchanged:$uid_unchanged,restart_count_unchanged:$restart_unchanged,
        snapshots:{
          baseline:{file:$baseline_file,sha256:$baseline_digest},
          outage:{file:$outage_file,sha256:$outage_digest},
          restored:{file:$restored_file,sha256:$restored_digest}
        }}')" \
    --slurpfile baseline "$evidence_dir/health/baseline.json" \
    --slurpfile outage "$evidence_dir/health/redis-outage.json" \
    --slurpfile restored "$evidence_dir/health/restored.json" \
    --argjson observed_seconds "$outage_observed_seconds" \
    --argjson redis_before "$original_redis_replicas" \
    --argjson redis_during "$redis_during_replicas" \
    --argjson redis_after "$redis_after_replicas" \
    '{schema_version:1,profile:$profile,status:"PASS",cluster:$cluster,context:$context,
      namespace:$namespace,namespace_run_id:$namespace_run_id,source_revision:$source_revision,
      target:{service:$service,container:$container,port:$port,pod:$pod,
        pod_uid:$pod_uid,node:$node,replicas:$replicas},
      placement:$placement,
      probes:{startup:{path:$startup_path,port:"http"},
        readiness:{path:$readiness_path,port:"http"},
        liveness:{path:$liveness_path,port:"http"}},
      observations:{baseline:$baseline[0],redis_outage:$outage[0],restored:$restored[0]},
      redis_outage:{deployment:"redis",replicas_before:$redis_before,
        replicas_during:$redis_during,replicas_after:$redis_after,
        observed_seconds:$observed_seconds,
        snapshots:{
          before:{file:$redis_before_file,sha256:$redis_before_digest,replicas:$redis_before},
          during:{file:$redis_during_file,sha256:$redis_during_digest,replicas:$redis_during},
          after:{file:$redis_after_file,sha256:$redis_after_digest,replicas:$redis_after}
        }},
      claim_boundary:["source-aligned query-service placement and serving",
        "query-service readiness and liveness remained healthy during a Redis outage",
        "no query-service Pod replacement or restart was observed during the bounded outage",
        "diagnostic-only evidence; not a full-local aggregate certification"]}' \
    >"$report_file"
  java_placement_serving_report_is_passed "$report_file" ||
    die 'published Java placement/serving report failed its evidence contract'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) namespace="${2:?--namespace requires a value}"; shift 2 ;;
    --namespace-run-id) namespace_run_id="${2:?--namespace-run-id requires a value}"; shift 2 ;;
    --retained-evidence-dir) retained_evidence_dir="${2:?--retained-evidence-dir requires a value}"; shift 2 ;;
    --evidence-dir) evidence_dir="${2:?--evidence-dir requires a value}"; shift 2 ;;
    --timeout-seconds) timeout_seconds="${2:?--timeout-seconds requires a value}"; shift 2 ;;
    --observe-seconds) observe_seconds="${2:?--observe-seconds requires a value}"; shift 2 ;;
    --help|-h) usage; trap - EXIT; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die '--timeout-seconds must be positive'
(( timeout_seconds <= 600 )) || die '--timeout-seconds must not exceed 600'
[[ "$observe_seconds" =~ ^[1-9][0-9]*$ ]] || die '--observe-seconds must be positive'
(( observe_seconds <= 30 )) || die '--observe-seconds must not exceed 30'
deadline_epoch=$((SECONDS + timeout_seconds))

prepare_evidence_dir
validate_namespace_and_provenance
validate_tools_and_cluster
printf 'Java placement observer preflight passed: cluster=%s context=%s namespace=%s run-id=%s\n' \
  "$cluster_name" "$context" "$namespace" "$namespace_run_id"

current_stage='capture baseline placement'
capture_placement baseline
java_placement_serving_probe_contract_is_valid \
  "$evidence_dir/placement/baseline-deployment.json" || die 'baseline probe contract is invalid'
target_pod="$(jq -er '.pods[0].name' "$evidence_dir/placement/baseline.json")" ||
  die 'could not select a unique query-service target Pod'
target_pod_uid="$(jq -er --arg pod "$target_pod" \
  '.pods[] | select(.name == $pod) | .uid' "$evidence_dir/placement/baseline.json")" ||
  die 'baseline target Pod UID is missing'
target_node="$(jq -er --arg pod "$target_pod" \
  '.pods[] | select(.name == $pod) | .node' "$evidence_dir/placement/baseline.json")" ||
  die 'baseline target Pod node is missing'
target_restart_count="$(jq -er --arg pod "$target_pod" \
  '.pods[] | select(.name == $pod) | .restart_count' "$evidence_dir/placement/baseline.json")" ||
  die 'baseline target restart count is missing'

redis_before_deployment_file="$evidence_dir/placement/redis-before-deployment.json"
kns get deployment redis -o json >"$redis_before_deployment_file" ||
  die 'cannot capture Redis baseline'
original_redis_replicas="$(redis_replicas "$redis_before_deployment_file")" ||
  die 'Redis replica count is invalid'
[[ "$original_redis_replicas" == 1 ]] || die 'representative Redis outage requires exactly one baseline replica'
wait_for_redis_replicas 1 || die 'Redis baseline is not Ready'
capture_redis_snapshot before 1

current_stage='capture baseline serving and health'
start_port_forward
capture_health baseline

current_stage='observe query-service during Redis outage'
redis_scaled=true
kns scale deployment/redis --replicas=0 >/dev/null || die 'Redis could not be scaled down'
wait_for_redis_replicas 0 || die 'Redis outage was not observed'
capture_redis_snapshot during 0
outage_started_seconds="$SECONDS"
run_bounded sleep "$observe_seconds" || die 'Redis outage observation window exceeded deadline'
outage_observed_seconds=$((SECONDS - outage_started_seconds))
(( outage_observed_seconds >= observe_seconds )) || die 'Redis outage observation window was incomplete'
capture_placement redis-outage
assert_target_identity_unchanged redis-outage
capture_health redis-outage

current_stage='restore Redis and verify serving'
kns scale deployment/redis "--replicas=$original_redis_replicas" >/dev/null ||
  die 'Redis could not be restored'
wait_for_redis_replicas "$original_redis_replicas" || die 'Redis did not become Ready after restoration'
redis_scaled=false
capture_redis_snapshot after "$original_redis_replicas"
capture_placement restored
assert_target_identity_unchanged restored
capture_health restored

current_stage='publish placement and serving evidence'
write_pass_report
report_published=true
printf 'Java placement/serving observer passed: %s\n' "$evidence_dir/java-placement-serving.json"
cleanup 0

#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

# Focused lifecycle diagnostics for the local PostgreSQL, Redis, and Kafka
# dependencies. The command deliberately consumes an existing, disposable
# namespace; it never applies manifests, changes offsets, or deletes a cluster.

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-resilience-dependencies.sh
source "$script_dir/lib/local-resilience-dependencies.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="${SIMPLEMATCH_KUBE_CONTEXT:-kind-$cluster_name}"
context_explicit=false
[[ -n "${SIMPLEMATCH_KUBE_CONTEXT:-}" ]] && context_explicit=true
namespace="${SIMPLEMATCH_RESILIENCE_NAMESPACE:-}"
namespace_run_id="${SIMPLEMATCH_RESILIENCE_NAMESPACE_RUN_ID:-}"
component=""
fault_mode=worker-stop
deadline_seconds=300
evidence_dir="${SIMPLEMATCH_RESILIENCE_DEPENDENCY_EVIDENCE_DIR:-}"
dry_run=false

run_id="$(date -u +%Y%m%dt%H%M%sz)-$$"
report_path=""
started_at_epoch=0
deadline_epoch=0
failure_reason=""
worker_node=""
worker_container_id=""
worker_container_id_after=""
worker_stopped=false
worker_not_ready_observed=false
same_container_restarted=false
marker_topic=""
marker_topic_created=false
kafka_marker_pod=""
marker_key=""
marker_value=""
emergency_cleanup_running=false

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/run-local-resilience-dependencies.sh \
    --component postgresql|redis|kafka --namespace NAME [options]

Options:
  --component NAME       Dependency to exercise (required).
  --namespace NAME       Existing lifecycle-labelled disposable namespace (required).
  --context NAME         Kubernetes context (default: kind-simplematch-live).
  --cluster NAME         Canonical kind cluster name (default: simplematch-live).
  --namespace-run-id ID  Require this exact namespace run-id label.
  --fault-mode MODE      worker-stop (default) or pod-restart.
  --deadline-seconds N   One monotonic deadline for the dynamic case (default: 300).
  --evidence-dir PATH    Empty directory for the focused diagnostic report.
  --dry-run              Print the preflight and fault plan without changing state.

The diagnostic validates exact Pod/Node/PVC/PV identity and component-specific
data contracts. It is local diagnostic evidence, not a full-local certification
PASS and not a claim of cross-node storage HA.
EOF_USAGE
}

die() {
  failure_reason="$*"
  printf 'Dependency resilience diagnostic: %s\n' "$*" >&2
  exit 1
}

now_epoch() {
  date +%s
}

check_deadline() {
  local now
  [[ "$deadline_epoch" -gt 0 ]] || return 0
  now="$(now_epoch)"
  (( now < deadline_epoch )) || die "dynamic case exceeded the ${deadline_seconds}s deadline"
}

kube() {
  check_deadline
  kubectl --context "$context" "$@"
}

kns() {
  check_deadline
  kubectl --context "$context" -n "$namespace" "$@"
}

cleanup_kube() {
  kubectl --context "$context" "$@"
}

cleanup_kns() {
  kubectl --context "$context" -n "$namespace" "$@"
}

write_failure_report() {
  local status="${1:-FAILED}"
  local reason="${failure_reason:-dependency diagnostic did not complete}"
  mkdir -p "$evidence_dir"
  jq -n \
    --argjson schema_version "$RESILIENCE_DEPENDENCY_REPORT_SCHEMA_VERSION" \
    --arg profile dependency-recovery --arg component "$component" --arg status "$status" \
    --arg cluster "$cluster_name" --arg context "$context" --arg namespace "$namespace" \
    --arg run_id "$run_id" --arg fault_mode "$fault_mode" \
    --argjson deadline_seconds "$deadline_seconds" --arg reason "$reason" \
    '{schema_version:$schema_version,profile:$profile,component:$component,status:$status,
      cluster:$cluster,context:$context,namespace:$namespace,run_id:$run_id,
      fault_mode:$fault_mode,deadline_seconds:$deadline_seconds,target:{},
      failure_reason:$reason,claim_boundary:["focused local dependency diagnostic"]}' \
    >"$report_path"
}

emergency_cleanup() {
  local status="$?"
  [[ "$emergency_cleanup_running" == false ]] || exit "$status"
  emergency_cleanup_running=true
  set +e
  if [[ "$worker_stopped" == true && -n "$worker_node" ]]; then
    docker start "$worker_node" >/dev/null 2>&1 || true
  fi
  if [[ "$marker_topic_created" == true && -n "$marker_topic" && -n "$kafka_marker_pod" ]]; then
    cleanup_kns exec "$kafka_marker_pod" -c kafka -- \
      /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
      --delete --if-exists --topic "$marker_topic" >/dev/null 2>&1 || true
  fi
  if [[ -n "$report_path" && ! -f "$report_path" ]]; then
    write_failure_report FAILED
  fi
  trap - EXIT
  exit "$status"
}

require_tools() {
  local tool
  for tool in docker kind kubectl jq; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
  done
  docker info >/dev/null 2>&1 || die 'Docker daemon is not reachable'
}

validate_namespace() {
  local namespace_json labels_manager labels_run_id

  namespace_json="$(kube get namespace "$namespace" -o json)" ||
    die "namespace is not available: $namespace"
  jq -e '
    .metadata.labels["simplematch.io/lifecycle"] == "disposable" and
    (.metadata.labels["simplematch.io/managed-by"] == "local-resilience" or
     .metadata.labels["simplematch.io/managed-by"] == "local-production-like-certification") and
    (.metadata.labels["simplematch.io/run-id"] | type == "string" and length > 0)
  ' <<<"$namespace_json" >/dev/null ||
    die "namespace is not an owned disposable resilience/certification namespace: $namespace"
  labels_manager="$(jq -r '.metadata.labels["simplematch.io/managed-by"]' <<<"$namespace_json")"
  labels_run_id="$(jq -r '.metadata.labels["simplematch.io/run-id"]' <<<"$namespace_json")"
  [[ "$labels_run_id" =~ ^[A-Za-z0-9._-]+$ ]] ||
    die 'namespace run-id contains unsupported characters'
  if [[ -n "$namespace_run_id" && "$labels_run_id" != "$namespace_run_id" ]]; then
    die "namespace belongs to run $labels_run_id, not $namespace_run_id"
  fi
  namespace_run_id="$labels_run_id"
  marker_value="${namespace_run_id}-${component}-${run_id}"
  marker_key="simplematch-resilience-${run_id}"
  marker_topic="simplematch-resilience-${run_id}"
  simplematch_info "Using $labels_manager namespace $namespace (run $namespace_run_id)."
}

validate_cluster_preflight() {
  local nodes_json current_context worker_count ready_workers control_plane_count

  current_context="$(kubectl config current-context 2>/dev/null || true)"
  [[ "$current_context" == "$context" ]] ||
    die "current Kubernetes context=$current_context, expected $context"
  kind get clusters | grep -Fxq "$cluster_name" ||
    die "canonical kind cluster is not available: $cluster_name"
  nodes_json="$(kube get nodes -o json)" || die 'could not read canonical kind nodes'
  worker_count="$(jq '[.items[] | select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience")] | length' <<<"$nodes_json")"
  ready_workers="$(jq '[.items[] | select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience") | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | length' <<<"$nodes_json")"
  control_plane_count="$(jq '[.items[] | select(.metadata.labels["node-role.kubernetes.io/control-plane"] == "")] | length' <<<"$nodes_json")"
  [[ "$(jq '.items | length' <<<"$nodes_json")" == 4 && "$worker_count" == 3 &&
    "$ready_workers" == 3 && "$control_plane_count" == 1 ]] ||
    die 'canonical topology is not one control plane plus three Ready workers'
  kube get --raw='/readyz?verbose' | grep -Fq 'readyz check passed' ||
    die 'canonical control plane is not reporting readyz success'
}

prepare_evidence_dir() {
  local existing
  evidence_dir="${evidence_dir:-$repo_root/out/resilience/dependencies-$run_id}"
  if [[ -e "$evidence_dir" ]]; then
    existing="$(find "$evidence_dir" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null || true)"
    [[ -z "$existing" ]] || die "evidence directory must be empty: $evidence_dir"
  fi
  mkdir -p "$evidence_dir"
  report_path="$evidence_dir/$component.json"
}

node_slot() {
  local node="$1"
  kube get node "$node" -o json | jq -er '.metadata.labels["simplematch.io/worker-slot"] // empty'
}

assert_pv_node_affinity() {
  local pv="$1" node="$2" pv_json
  pv_json="$(kube get pv "$pv" -o json)" || die "could not read PV $pv"
  jq -e --arg node "$node" '
    any(.spec.nodeAffinity.required.nodeSelectorTerms[]?.matchExpressions[]?;
      .key == "kubernetes.io/hostname" and any(.values[]?; . == $node))
  ' <<<"$pv_json" >/dev/null || die "PV $pv has no node affinity for $node"
}

assert_worker_node() {
  local node="$1" expected_slot="${2:-}"
  local node_json node_pool slot role cluster

  node_json="$(kube get node "$node" -o json)" || die "worker node is missing: $node"
  node_pool="$(jq -r '.metadata.labels["simplematch.io/node-pool"] // empty' <<<"$node_json")"
  slot="$(jq -r '.metadata.labels["simplematch.io/worker-slot"] // empty' <<<"$node_json")"
  [[ "$node_pool" == local-resilience ]] || die "target node is outside local-resilience: $node"
  [[ -z "$expected_slot" || "$slot" == "$expected_slot" ]] ||
    die "target node $node has slot $slot, expected $expected_slot"
  cluster="$(docker inspect --format '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$node" 2>/dev/null || true)"
  role="$(docker inspect --format '{{index .Config.Labels "io.x-k8s.kind.role"}}' "$node" 2>/dev/null || true)"
  [[ "$cluster" == "$cluster_name" && "$role" == worker ]] ||
    die "target node is not a worker owned by $cluster_name: $node"
}

wait_for_node_ready() {
  local node="$1" ready
  while true; do
    check_deadline
    ready="$(kube get node "$node" -o json 2>/dev/null | jq -r 'any(.status.conditions[]?; .type == "Ready" and .status == "True")' 2>/dev/null || true)"
    [[ "$ready" == true ]] && return 0
    sleep 2
  done
}

wait_for_node_not_ready() {
  local node="$1" ready
  while true; do
    check_deadline
    ready="$(kube get node "$node" -o json 2>/dev/null | jq -r 'any(.status.conditions[]?; .type == "Ready" and .status == "True")' 2>/dev/null || true)"
    if [[ "$ready" != true ]]; then
      worker_not_ready_observed=true
      return 0
    fi
    sleep 2
  done
}

wait_for_postgres_pod_ready() {
  local previous_uid="$1" pod_json ready uid
  while true; do
    check_deadline
    pod_json="$(kns get pod postgres-0 -o json 2>/dev/null || true)"
    ready="$(jq -r 'any(.status.conditions[]?; .type == "Ready" and .status == "True")' <<<"$pod_json" 2>/dev/null || true)"
    uid="$(jq -r '.metadata.uid // empty' <<<"$pod_json" 2>/dev/null || true)"
    if [[ "$ready" == true && -n "$uid" && ( -z "$previous_uid" || "$uid" != "$previous_uid" ) ]]; then
      return 0
    fi
    sleep 2
  done
}

wait_for_redis_pod_ready() {
  local previous_uid="$1" pods_json pod_json ready uid
  while true; do
    check_deadline
    pods_json="$(kns get pods -l app.kubernetes.io/name=redis -o json 2>/dev/null || true)"
    pod_json="$(jq -c '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | if length == 1 then .[0] else {} end' <<<"$pods_json" 2>/dev/null || true)"
    ready="$(jq -r 'any(.status.conditions[]?; .type == "Ready" and .status == "True")' <<<"$pod_json" 2>/dev/null || true)"
    uid="$(jq -r '.metadata.uid // empty' <<<"$pod_json" 2>/dev/null || true)"
    if [[ "$ready" == true && -n "$uid" && ( -z "$previous_uid" || "$uid" != "$previous_uid" ) ]]; then
      return 0
    fi
    sleep 2
  done
}

wait_for_kafka_set_ready() {
  local previous_uid="$1" pods_json ready_count target_uid
  while true; do
    check_deadline
    pods_json="$(kns get pods -l app.kubernetes.io/name=kafka,app.kubernetes.io/component=broker -o json 2>/dev/null || true)"
    ready_count="$(jq '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | length' <<<"$pods_json" 2>/dev/null || true)"
    target_uid="$(jq -r '.items[]? | select(.metadata.name == "kafka-1") | .metadata.uid // empty' <<<"$pods_json" 2>/dev/null || true)"
    if [[ "$ready_count" == 3 && -n "$target_uid" && ( -z "$previous_uid" || "$target_uid" != "$previous_uid" ) ]]; then
      return 0
    fi
    sleep 2
  done
}

worker_stop_evidence_json() {
  jq -n \
    --arg node "$worker_node" --arg container_id "$worker_container_id" \
    --arg container_id_after "$worker_container_id_after" \
    --argjson node_not_ready_observed "$worker_not_ready_observed" \
    --argjson same_container_restarted "$same_container_restarted" \
    '{node:$node,container_id:$container_id,container_id_after:$container_id_after,
      node_not_ready_observed:$node_not_ready_observed,
      same_container_restarted:$same_container_restarted}'
}

inject_fault() {
  local target_pod="$1"
  local target_node="$2"

  if [[ "$fault_mode" == pod-restart ]]; then
    kns delete pod "$target_pod" --wait=false >/dev/null ||
      die "could not request Pod restart for $target_pod"
    return 0
  fi

  worker_node="$target_node"
  assert_worker_node "$worker_node"
  worker_container_id="$(docker inspect --format '{{.Id}}' "$worker_node" 2>/dev/null || true)"
  [[ "$worker_container_id" =~ ^[0-9a-f]{64}$ ]] ||
    die "worker container identity is incomplete: $worker_node"
  docker stop --time 0 "$worker_node" >/dev/null || die "could not stop worker $worker_node"
  worker_stopped=true
  wait_for_node_not_ready "$worker_node"
}

restore_worker() {
  [[ "$worker_stopped" == true ]] || return 0
  docker start "$worker_node" >/dev/null || die "could not restart worker $worker_node"
  worker_container_id_after="$(docker inspect --format '{{.Id}}' "$worker_node" 2>/dev/null || true)"
  [[ "$worker_container_id_after" == "$worker_container_id" ]] ||
    die 'worker restart returned a different Docker container identity'
  same_container_restarted=true
  worker_stopped=false
  wait_for_node_ready "$worker_node"
}

postgres_query() {
  local query="$1"
  kns exec "$postgres_pod" -c postgres -- psql \
    --username=simplematch --dbname=simplematch --no-psqlrc \
    --tuples-only --no-align --set=ON_ERROR_STOP=1 --command "$query"
}

capture_postgres_identity() {
  local destination_name="$1"
  local pods_json pod_json node uid slot pvc pvc_json pv ready
  pods_json="$(kns get pods -l app.kubernetes.io/name=postgres -o json)" || die 'could not read PostgreSQL Pods'
  pod_json="$(jq -e '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | if length == 1 then .[0] else empty end' <<<"$pods_json")" ||
    die 'PostgreSQL must have exactly one Ready Pod'
  pod="$(jq -r '.metadata.name' <<<"$pod_json")"
  [[ "$pod" == postgres-0 ]] || die "unexpected PostgreSQL Pod identity: $pod"
  node="$(jq -r '.spec.nodeName // empty' <<<"$pod_json")"
  uid="$(jq -r '.metadata.uid // empty' <<<"$pod_json")"
  slot="$(node_slot "$node")" || die "PostgreSQL node has no worker slot: $node"
  [[ "$slot" == 0 ]] || die "PostgreSQL is not on worker slot 0: $node"
  pvc="$(jq -er '.spec.volumes[] | select(.name == "postgres-data") | .persistentVolumeClaim.claimName' <<<"$pod_json")" ||
    die 'PostgreSQL Pod has no postgres-data PVC'
  pvc_json="$(kns get pvc "$pvc" -o json)" || die "could not read PostgreSQL PVC $pvc"
  [[ "$(jq -r '.status.phase' <<<"$pvc_json")" == Bound ]] || die "PostgreSQL PVC is not Bound: $pvc"
  pv="$(jq -r '.spec.volumeName // empty' <<<"$pvc_json")"
  [[ -n "$pv" ]] || die "PostgreSQL PVC has no PV: $pvc"
  assert_pv_node_affinity "$pv" "$node"
  ready="$(jq -r 'any(.status.conditions[]?; .type == "Ready" and .status == "True")' <<<"$pod_json")"
  [[ "$ready" == true && -n "$uid" && -n "$node" ]] || die 'PostgreSQL identity is incomplete'
  printf -v "$destination_name" '%s' "$(jq -n \
    --arg pod "$pod" --arg pod_uid "$uid" --arg node "$node" --arg worker_slot "$slot" \
    --arg pvc "$pvc" --arg pv "$pv" \
    '{pod:$pod,pod_uid:$pod_uid,node:$node,worker_slot:$worker_slot,pvc:$pvc,pv:$pv}')"
  postgres_pod="$pod"
}

run_postgresql() {
  local before after marker_before marker_after durable_before durable_after
  local target_node target_pod worker_json report_json previous_uid=""
  local sql

  capture_postgres_identity before
  target_pod="$(jq -r '.pod' <<<"$before")"
  target_node="$(jq -r '.node' <<<"$before")"
  sql="CREATE TABLE IF NOT EXISTS simplematch_local_resilience_marker (run_id text PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now()); INSERT INTO simplematch_local_resilience_marker(run_id) VALUES ('$marker_value') ON CONFLICT (run_id) DO NOTHING; SELECT count(*) FROM simplematch_local_resilience_marker WHERE run_id = '$marker_value';"
  marker_before="$(postgres_query "$sql" | tail -n 1 | tr -d '[:space:]')" || die 'could not write PostgreSQL durable marker'
  [[ "$marker_before" == 1 ]] || die 'PostgreSQL durable marker was not committed'
  durable_before=true

  inject_fault "$target_pod" "$target_node"
  restore_worker
  [[ "$fault_mode" == pod-restart ]] && previous_uid="$(jq -r '.pod_uid' <<<"$before")"
  wait_for_postgres_pod_ready "$previous_uid"
  capture_postgres_identity after
  marker_after="$(postgres_query "SELECT count(*) FROM simplematch_local_resilience_marker WHERE run_id = '$marker_value';" | tr -d '[:space:]')" ||
    die 'could not read PostgreSQL durable marker after recovery'
  durable_after=false
  [[ "$marker_after" == 1 ]] && durable_after=true
  [[ "$durable_after" == true ]] || die 'PostgreSQL durable marker disappeared after recovery'
  [[ "$(jq -r '.node' <<<"$before")" == "$(jq -r '.node' <<<"$after")" ]] ||
    die 'PostgreSQL moved away from its node-local worker'
  [[ "$(jq -r '.pvc' <<<"$before")" == "$(jq -r '.pvc' <<<"$after")" &&
    "$(jq -r '.pv' <<<"$before")" == "$(jq -r '.pv' <<<"$after")" ]] ||
    die 'PostgreSQL did not retain its original PVC/PV'

  if [[ "$fault_mode" == worker-stop ]]; then
    worker_json="$(worker_stop_evidence_json)"
  else
    worker_json='null'
  fi
  report_json="$(jq -n \
    --argjson before "$before" --argjson after "$after" \
    --argjson worker_stop "$worker_json" --arg marker "$marker_value" \
    --argjson durable_before "$durable_before" --argjson durable_after "$durable_after" \
    --arg cluster "$cluster_name" --arg context "$context" --arg namespace "$namespace" \
    --arg run_id "$run_id" --arg fault_mode "$fault_mode" --argjson deadline "$deadline_seconds" \
    '{schema_version:1,profile:"dependency-recovery",component:"postgresql",status:"PASSED",
      cluster:$cluster,context:$context,namespace:$namespace,run_id:$run_id,fault_mode:$fault_mode,
      deadline_seconds:$deadline,target:{before:$before,after:$after},
      worker_stop:$worker_stop,
      recovery:{ready:true,durable_marker:$marker,durable_before:$durable_before,
        durable_after:$durable_after,data_preserved:true},failure_reason:null,
      claim_boundary:["local PostgreSQL same-worker PVC and durable-row recovery"]}')"
  printf '%s\n' "$report_json" >"$report_path"
  resilience_dependency_report_is_passed postgresql "$report_path" || die 'PostgreSQL report failed its evidence contract'
}

redis_command() {
  local pod="$1"
  shift
  kns exec "$pod" -c redis -- "$@"
}

capture_redis_identity() {
  local destination_name="$1"
  local pods_json pod_json pod node uid slot volumes pvc ready deployment_json
  pods_json="$(kns get pods -l app.kubernetes.io/name=redis -o json)" || die 'could not read Redis Pods'
  pod_json="$(jq -e '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | if length == 1 then .[0] else empty end' <<<"$pods_json")" ||
    die 'Redis must have exactly one Ready Pod'
  pod="$(jq -r '.metadata.name' <<<"$pod_json")"
  node="$(jq -r '.spec.nodeName // empty' <<<"$pod_json")"
  uid="$(jq -r '.metadata.uid // empty' <<<"$pod_json")"
  slot="$(node_slot "$node")" || die "Redis node has no worker slot: $node"
  volumes="$(jq -c '.spec.volumes // []' <<<"$pod_json")"
  jq -e 'any(.[]; .name == "redis-data" and .emptyDir == {}) and all(.[]; .persistentVolumeClaim == null)' <<<"$volumes" >/dev/null ||
    die 'Redis state is not an emptyDir without a PVC'
  deployment_json="$(kns get deployment redis -o json)" || die 'could not read Redis Deployment'
  jq -e '
    .spec.replicas == 1 and .spec.strategy.type == "Recreate" and
    (.spec.template.spec.nodeSelector["simplematch.io/node-pool"] == "local-resilience") and
    (.spec.template.spec.nodeSelector["simplematch.io/worker-slot"] == null)
  ' <<<"$deployment_json" >/dev/null || die 'Redis Deployment is not portable'
  ready="$(jq -r 'any(.status.conditions[]?; .type == "Ready" and .status == "True")' <<<"$pod_json")"
  [[ "$ready" == true && -n "$uid" && -n "$node" ]] || die 'Redis identity is incomplete'
  printf -v "$destination_name" '%s' "$(jq -n \
    --arg pod "$pod" --arg pod_uid "$uid" --arg node "$node" --arg worker_slot "$slot" \
    '{pod:$pod,pod_uid:$pod_uid,node:$node,worker_slot:$worker_slot,pvc:null}')"
  redis_pod="$pod"
}

run_redis() {
  local before after target_pod target_node marker_before marker_after
  local marker_after_bool=false worker_json report_json previous_uid=""

  capture_redis_identity before
  target_pod="$(jq -r '.pod' <<<"$before")"
  target_node="$(jq -r '.node' <<<"$before")"
  marker_before="$(redis_command "$target_pod" redis-cli SET "$marker_key" "$marker_value" EX 600 2>/dev/null | tr -d '[:space:]')" ||
    die 'could not write Redis disposable marker'
  [[ "$marker_before" == OK ]] || die 'Redis disposable marker was not accepted'
  inject_fault "$target_pod" "$target_node"
  restore_worker
  [[ "$fault_mode" == pod-restart ]] && previous_uid="$(jq -r '.pod_uid' <<<"$before")"
  wait_for_redis_pod_ready "$previous_uid"
  capture_redis_identity after
  marker_after="$(redis_command "$redis_pod" redis-cli GET "$marker_key" 2>/dev/null | tr -d '\r')" || true
  [[ "$marker_after" == "$marker_value" ]] && marker_after_bool=true
  if [[ "$fault_mode" == worker-stop ]]; then
    worker_json="$(worker_stop_evidence_json)"
  else
    worker_json='null'
  fi
  report_json="$(jq -n \
    --argjson before "$before" --argjson after "$after" --argjson worker_stop "$worker_json" \
    --argjson marker_before true --argjson marker_after "$marker_after_bool" \
    --arg cluster "$cluster_name" --arg context "$context" --arg namespace "$namespace" \
    --arg run_id "$run_id" --arg fault_mode "$fault_mode" --argjson deadline "$deadline_seconds" \
    '{schema_version:1,profile:"dependency-recovery",component:"redis",status:"PASSED",
      cluster:$cluster,context:$context,namespace:$namespace,run_id:$run_id,fault_mode:$fault_mode,
      deadline_seconds:$deadline,target:{before:$before,after:$after},worker_stop:$worker_stop,
      recovery:{ready:true,portable:true,disposable_state:true,marker_before:$marker_before,
        marker_after:$marker_after,marker_required_after:false},failure_reason:null,
      claim_boundary:["local Redis readiness after portable worker recovery","Redis state is disposable"]}')"
  printf '%s\n' "$report_json" >"$report_path"
  resilience_dependency_report_is_passed redis "$report_path" || die 'Redis report failed its evidence contract'
}

kafka_command() {
  local pod="$1"
  shift
  kns exec "$pod" -c kafka -- "$@"
}

capture_kafka_set() {
  local destination_name="$1"
  local output='[]' ordinal pod_json pod node uid slot pvc pvc_json pv cluster_id node_id meta item
  local metadata_awk="\$1 == \"cluster.id\" || \$1 == \"node.id\" { print \$1 \"=\" \$2 }"
  for ordinal in 0 1 2; do
    check_deadline
    pod="kafka-$ordinal"
    pod_json="$(kns get pod "$pod" -o json)" || die "could not read Kafka Pod $pod"
    jq -e --arg ordinal "$ordinal" '
      .metadata.name == ("kafka-" + $ordinal) and
      .metadata.labels["apps.kubernetes.io/pod-index"] == $ordinal and
      any(.status.conditions[]?; .type == "Ready" and .status == "True")
    ' <<<"$pod_json" >/dev/null || die "Kafka Pod is not Ready or has the wrong ordinal: $pod"
    node="$(jq -r '.spec.nodeName // empty' <<<"$pod_json")"
    uid="$(jq -r '.metadata.uid // empty' <<<"$pod_json")"
    slot="$(node_slot "$node")" || die "Kafka node has no worker slot: $node"
    pvc="$(jq -er '.spec.volumes[] | select(.name == "kafka-data") | .persistentVolumeClaim.claimName' <<<"$pod_json")" ||
      die "Kafka Pod $pod has no kafka-data PVC"
    pvc_json="$(kns get pvc "$pvc" -o json)" || die "could not read Kafka PVC $pvc"
    [[ "$(jq -r '.status.phase' <<<"$pvc_json")" == Bound ]] || die "Kafka PVC is not Bound: $pvc"
    pv="$(jq -r '.spec.volumeName // empty' <<<"$pvc_json")"
    [[ -n "$pv" ]] || die "Kafka PVC has no PV: $pvc"
    assert_pv_node_affinity "$pv" "$node"
    meta="$(kafka_command "$pod" awk -F= "$metadata_awk" /var/lib/kafka/data/meta.properties)" ||
      die "could not read Kafka metadata for $pod"
    cluster_id="$(awk -F= '$1 == "cluster.id" { print $2 }' <<<"$meta")"
    node_id="$(awk -F= '$1 == "node.id" { print $2 }' <<<"$meta")"
    [[ "$cluster_id" == "$RESILIENCE_DEPENDENCY_KAFKA_CLUSTER_ID" && "$node_id" == "$ordinal" ]] ||
      die "Kafka metadata mismatch for $pod"
    item="$(jq -n \
      --arg pod "$pod" --arg pod_uid "$uid" --arg node "$node" --arg worker_slot "$slot" \
      --arg pvc "$pvc" --arg pv "$pv" --arg cluster_id "$cluster_id" --argjson node_id "$node_id" \
      '{pod:$pod,pod_uid:$pod_uid,node:$node,worker_slot:$worker_slot,pvc:$pvc,pv:$pv,cluster_id:$cluster_id,node_id:$node_id}')"
    output="$(jq --argjson item "$item" '. + [$item]' <<<"$output")"
  done
  printf -v "$destination_name" '%s' "$output"
}

kafka_describe() {
  local pod="$1"
  kafka_command "$pod" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
    --describe --topic "$marker_topic"
}

kafka_isr_count() {
  local description="$1" isr
  isr="$(sed -nE 's/.*[[:space:]]Isr:[[:space:]]*([^[:space:]]+).*/\1/p' <<<"$description" | head -n 1)"
  [[ -n "$isr" ]] || return 1
  awk -F, '{ print NF }' <<<"$isr"
}

kafka_marker_count() {
  local pod="$1" output
  output="$(kafka_command "$pod" /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:9092 --topic "$marker_topic" --from-beginning \
    --timeout-ms 5000 --property print.key=true --property key.separator='|' 2>/dev/null || true)"
  grep -Fxc "$marker_key|$marker_value" <<<"$output" || true
}

kafka_ready_brokers_excluding() {
  local excluded_node="$1"
  kns get pods -l app.kubernetes.io/name=kafka,app.kubernetes.io/component=broker -o json |
    jq --arg excluded "$excluded_node" '[.items[] | select(.spec.nodeName != $excluded) | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | length'
}

create_kafka_marker() {
  local pod="$1" created=false
  if kafka_command "$pod" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
    --describe --topic "$marker_topic" >/dev/null 2>&1; then
    die "run-owned Kafka marker topic already exists: $marker_topic"
  fi
  for _ in $(seq 1 30); do
    check_deadline
    if kafka_command "$pod" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
      --create --if-not-exists --topic "$marker_topic" --partitions 1 --replication-factor 3 \
      --config cleanup.policy=delete --config retention.ms=600000 --config min.insync.replicas=2 >/dev/null 2>&1; then
      created=true
      break
    fi
    sleep 2
  done
  [[ "$created" == true ]] || die "could not create Kafka marker topic $marker_topic"
  marker_topic_created=true
  kafka_marker_pod="$pod"
  printf '%s|%s\n' "$marker_key" "$marker_value" |
    kns exec -i "$pod" -c kafka -- /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server kafka:9092 --topic "$marker_topic" \
      --property parse.key=true --property key.separator='|' >/dev/null ||
    die 'could not commit Kafka marker record'
  for _ in $(seq 1 30); do
    check_deadline
    [[ "$(kafka_marker_count "$pod")" -ge 1 ]] && return 0
    sleep 2
  done
  die 'Kafka marker record was not visible before fault injection'
}

delete_kafka_marker() {
  local pod="$1" deleted=false
  for _ in $(seq 1 15); do
    if kafka_command "$pod" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
      --delete --if-exists --topic "$marker_topic" >/dev/null 2>&1; then
      deleted=true
      break
    fi
    sleep 1
  done
  [[ "$deleted" == true ]] || die "could not clean up run-owned Kafka topic $marker_topic"
  marker_topic_created=false
}

run_kafka() {
  local before after target_pod target_node marker_count_before marker_count_after
  local isr_before isr_during isr_after available_during worker_json report_json
  local target_before target_after previous_uid=""
  capture_kafka_set before
  target_pod=kafka-1
  target_before="$(jq -e --arg pod "$target_pod" '.[] | select(.pod == $pod)' <<<"$before")" ||
    die 'Kafka target broker kafka-1 is missing from baseline'
  target_node="$(jq -r '.node' <<<"$target_before")"
  create_kafka_marker "$(jq -r '.[0].pod' <<<"$before")"
  marker_count_before="$(kafka_marker_count "$(jq -r '.[0].pod' <<<"$before")")"
  [[ "$marker_count_before" =~ ^[1-9][0-9]*$ ]] || die 'Kafka marker count before fault is invalid'
  isr_before="$(kafka_isr_count "$(kafka_describe "$(jq -r '.[0].pod' <<<"$before")")")" ||
    die 'could not capture Kafka ISR before fault'
  [[ "$isr_before" == 3 ]] || die "Kafka marker topic ISR before fault is $isr_before, expected 3"
  inject_fault "$target_pod" "$target_node"
  isr_during=""
  available_during=""
  while true; do
    check_deadline
    isr_during="$(kafka_isr_count "$(kafka_describe "$(jq -r '.[0].pod' <<<"$before")")" 2>/dev/null || true)"
    available_during="$(kafka_ready_brokers_excluding "$target_node" 2>/dev/null || true)"
    if [[ "$isr_during" == 2 && "$available_during" == 2 ]]; then
      break
    fi
    sleep 2
  done
  restore_worker
  [[ "$fault_mode" == pod-restart ]] && previous_uid="$(jq -r '.pod_uid' <<<"$target_before")"
  wait_for_kafka_set_ready "$previous_uid"
  capture_kafka_set after
  target_after="$(jq -e --arg pod "$target_pod" '.[] | select(.pod == $pod)' <<<"$after")" ||
    die 'Kafka target broker is missing after recovery'
  [[ "$(jq -r '.node' <<<"$target_before")" == "$(jq -r '.node' <<<"$target_after")" ]] ||
    die 'Kafka broker moved away from its node-local worker'
  [[ "$(jq -r '.pvc' <<<"$target_before")" == "$(jq -r '.pvc' <<<"$target_after")" &&
    "$(jq -r '.pv' <<<"$target_before")" == "$(jq -r '.pv' <<<"$target_after")" ]] ||
    die 'Kafka broker did not retain its original PVC/PV'
  isr_after="$(kafka_isr_count "$(kafka_describe "$(jq -r '.[0].pod' <<<"$after")")")" ||
    die 'could not capture Kafka ISR after recovery'
  [[ "$isr_after" == 3 ]] || die "Kafka marker topic ISR after recovery is $isr_after, expected 3"
  marker_count_after="$(kafka_marker_count "$(jq -r '.[0].pod' <<<"$after")")"
  [[ "$marker_count_after" =~ ^[1-9][0-9]*$ ]] || die 'Kafka marker record disappeared after recovery'
  delete_kafka_marker "$(jq -r '.[0].pod' <<<"$after")"
  if [[ "$fault_mode" == worker-stop ]]; then
    worker_json="$(worker_stop_evidence_json)"
  else
    worker_json='null'
  fi
  report_json="$(jq -n \
    --argjson before "$before" --argjson after "$after" \
    --argjson target_before "$target_before" --argjson target_after "$target_after" \
    --argjson worker_stop "$worker_json" \
    --argjson available_during "$available_during" --argjson isr_before "$isr_before" \
    --argjson isr_after "$isr_after" --argjson marker_count_before "$marker_count_before" \
    --argjson marker_count_after "$marker_count_after" --arg topic "$marker_topic" --arg key "$marker_key" \
    --arg cluster "$cluster_name" --arg context "$context" --arg namespace "$namespace" \
    --arg run_id "$run_id" --arg fault_mode "$fault_mode" --argjson deadline "$deadline_seconds" \
    '{schema_version:1,profile:"dependency-recovery",component:"kafka",status:"PASSED",
      cluster:$cluster,context:$context,namespace:$namespace,run_id:$run_id,fault_mode:$fault_mode,
      deadline_seconds:$deadline,target:{ordinal:1,before:$target_before,after:$target_after},
      brokers_before:$before,brokers_after:$after,worker_stop:$worker_stop,
      quorum:{ready_before:true,available_during:$available_during,isr_before:$isr_before,
        isr_after:$isr_after,restored:true},
      marker:{topic:$topic,key:$key,committed_before:true,preserved_after:true,
        record_count_before:$marker_count_before,record_count_after:$marker_count_after},
      recovery:{ready:true,rejoined:true,formatted_again:false,catch_up_complete:true},
      failure_reason:null,claim_boundary:["local Kafka RF3 committed-marker recovery after one worker stop"]}')"
  printf '%s\n' "$report_json" >"$report_path"
  resilience_dependency_report_is_passed kafka "$report_path" || die 'Kafka report failed its evidence contract'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --component) component="${2:?--component requires a value}"; shift 2 ;;
    --namespace) namespace="${2:?--namespace requires a value}"; shift 2 ;;
    --context) context="${2:?--context requires a value}"; context_explicit=true; shift 2 ;;
    --cluster) cluster_name="${2:?--cluster requires a value}"; shift 2 ;;
    --namespace-run-id) namespace_run_id="${2:?--namespace-run-id requires a value}"; shift 2 ;;
    --fault-mode) fault_mode="${2:?--fault-mode requires worker-stop or pod-restart}"; shift 2 ;;
    --deadline-seconds) deadline_seconds="${2:?--deadline-seconds requires a positive integer}"; shift 2 ;;
    --evidence-dir) evidence_dir="${2:?--evidence-dir requires a path}"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

resilience_dependency_valid_component "$component" || { usage >&2; die 'component must be postgresql, redis, or kafka'; }
case "$fault_mode" in
  worker-stop|pod-restart) ;;
  *) die 'fault mode must be worker-stop or pod-restart' ;;
esac
[[ "$deadline_seconds" =~ ^[1-9][0-9]*$ && "$deadline_seconds" -le 300 ]] ||
  die 'deadline-seconds must be a positive integer no greater than 300'
[[ -n "$namespace" ]] || die '--namespace is required'
[[ "$namespace" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || die 'namespace is not a valid Kubernetes name'
evidence_dir="${evidence_dir:-$repo_root/out/resilience/dependencies-$run_id}"
report_path="$evidence_dir/$component.json"

if [[ "$context_explicit" == false ]]; then
  context="kind-$cluster_name"
fi

if [[ "$dry_run" == true ]]; then
  printf 'DRY RUN: component=%s cluster=%s context=%s namespace=%s fault=%s deadline=%ss\n' \
    "$component" "$cluster_name" "$context" "$namespace" "$fault_mode" "$deadline_seconds"
  printf '%s\n' 'DRY RUN: verify Docker/context/topology/namespace ownership, capture exact identity, write a marker, inject one bounded fault, restore, validate evidence, and clean only run-owned marker state.'
  exit 0
fi

trap emergency_cleanup EXIT
prepare_evidence_dir
require_tools
validate_cluster_preflight
validate_namespace
started_at_epoch="$(now_epoch)"
deadline_epoch=$((started_at_epoch + deadline_seconds))

case "$component" in
  postgresql) run_postgresql ;;
  redis) run_redis ;;
  kafka) run_kafka ;;
esac

printf 'Dependency resilience diagnostic passed: %s\n' "$report_path"

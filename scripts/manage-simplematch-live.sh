#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"
# shellcheck source=scripts/lib/local-registry.sh
source "$script_dir/lib/local-registry.sh"
# shellcheck source=scripts/lib/local-resource.sh
source "$script_dir/lib/local-resource.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="kind-$cluster_name"
config="$repo_root/deploy/kind/simplematch-live.yaml"
storage_manifest="$repo_root/deploy/kind/simplematch-live-storageclass.yaml"
storage_class=simplematch-rwo-pod
resource_baseline_file="${SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_FILE:-$repo_root/out/local-resource-baseline.json}"
resource_baseline_timeout="${SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_TIMEOUT_SECONDS:-120}"
kubelet_image_minimum_gc_age=10m0s
kubelet_image_maximum_gc_age=24h0m0s
kubelet_image_gc_high_threshold=80
kubelet_image_gc_low_threshold=70
SIMPLEMATCH_DRY_RUN=false

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/manage-simplematch-live.sh {create|verify|delete} [--dry-run]

Manage the repository-owned canonical one-control-plane, three-worker kind lab.
create also creates/connects the repository-owned local registry, verifies the
cluster-wide local kubelet image-GC policy, and records a clean resource baseline
after topology/storage verification and probe cleanup. The registry cache
survives cluster deletion so a rebuilt cluster can pull current images without
re-publishing them. Normal resilience runs reuse the cluster; delete is reserved
for explicit rebuilds.
EOF_USAGE
}

require_tools() {
  for tool in kind kubectl jq docker findmnt; do
    simplematch_require_command "$tool"
  done
}

verify_docker_storage() {
  local docker_root filesystem
  docker_root="$(docker info --format '{{.DockerRootDir}}')" || simplematch_die 'cannot inspect Docker root'
  [[ -n "$docker_root" ]] || simplematch_die 'Docker root is empty'
  filesystem="$(findmnt -T "$docker_root" -no FSTYPE 2>/dev/null || true)"
  [[ -n "$filesystem" ]] || simplematch_die "cannot determine filesystem for Docker root $docker_root"
  case "$filesystem" in
    ntfs|ntfs3|exfat|vfat|fuseblk|cifs|smb3)
      simplematch_die "Docker root $docker_root uses unsupported filesystem $filesystem; move Docker data to a Linux-backed filesystem before creating kind"
      ;;
  esac
}

exists() {
  kind get clusters 2>/dev/null | grep -Fxq "$cluster_name"
}

nodes_json() {
  kubectl --context "$context" get nodes -o json
}

verify_topology() {
  local data="$1" node slot expected_role
  local -a nodes
  mapfile -t nodes < <(kind get nodes --name "$cluster_name")
  [[ ${#nodes[@]} -eq 4 ]] || simplematch_die 'canonical cluster must have four kind nodes'
  printf '%s\n' "${nodes[@]}" | grep -Fxq "${cluster_name}-control-plane" ||
    simplematch_die 'canonical control-plane container is missing'
  for node in "${cluster_name}-worker" "${cluster_name}-worker2" "${cluster_name}-worker3"; do
    printf '%s\n' "${nodes[@]}" | grep -Fxq "$node" || simplematch_die "canonical worker container $node is missing"
  done
  [[ "$(jq '.items | length' <<<"$data")" -eq 4 ]] || simplematch_die 'canonical cluster must have four Kubernetes Nodes'
  [[ "$(jq '[.items[] | select(.metadata.labels["node-role.kubernetes.io/control-plane"] == "")] | length' <<<"$data")" -eq 1 ]] || simplematch_die 'canonical cluster must have one control plane'
  [[ "$(jq '[.items[] | select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience")] | length' <<<"$data")" -eq 3 ]] || simplematch_die 'canonical cluster must have three local-resilience workers'
  jq -e '.items[] | select(.metadata.labels["node-role.kubernetes.io/control-plane"] == "") | any(.spec.taints[]?; .key == "node-role.kubernetes.io/control-plane" and .effect == "NoSchedule")' <<<"$data" >/dev/null || simplematch_die 'control plane is not tainted NoSchedule'
  for slot in 0 1 2; do
    node="$(jq -r --arg slot "$slot" '[.items[] | select(.metadata.labels["simplematch.io/worker-slot"] == $slot) | .metadata.name] | if length == 1 then .[0] else empty end' <<<"$data")"
    [[ -n "$node" ]] || simplematch_die "worker slot $slot is missing or not unique"
    jq -e --arg node "$node" '.items[] | select(.metadata.name == $node) | any(.status.conditions[]?; .type == "Ready" and .status == "True")' <<<"$data" >/dev/null || simplematch_die "worker slot $slot is not Ready"
  done
  for node in "${nodes[@]}"; do
    docker inspect "$node" >/dev/null 2>&1 || simplematch_die "missing Docker container for $node"
    [[ "$(docker inspect --format '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$node")" == "$cluster_name" ]] ||
      simplematch_die "Docker container $node is not owned by $cluster_name"
    expected_role=worker
    [[ "$node" == "${cluster_name}-control-plane" ]] && expected_role=control-plane
    [[ "$(docker inspect --format '{{index .Config.Labels "io.x-k8s.kind.role"}}' "$node")" == "$expected_role" ]] ||
      simplematch_die "Docker container $node has the wrong kind role"
  done
}

kubelet_config_value() {
  local node="$1"
  local key="$2"

  docker exec "$node" awk -v wanted="$key" '
    $1 == wanted ":" {
      count += 1
      value = $2
    }
    END {
      if (count != 1 || value == "") exit 1
      print value
    }
  ' /var/lib/kubelet/config.yaml
}

assert_kubelet_config_value() {
  local node="$1"
  local key="$2"
  local expected="$3"
  local actual

  actual="$(kubelet_config_value "$node" "$key")" ||
    simplematch_die "cannot read kubelet $key from $node"
  [[ "$actual" == "$expected" ]] ||
    simplematch_die "kubelet $key mismatch on $node: expected $expected, got $actual"
}

verify_kubelet_image_gc_policy() {
  local node
  local -a nodes

  mapfile -t nodes < <(kind get nodes --name "$cluster_name")
  [[ ${#nodes[@]} -eq 4 ]] || simplematch_die 'cannot verify kubelet image GC policy without four canonical nodes'
  for node in "${nodes[@]}"; do
    assert_kubelet_config_value "$node" imageMinimumGCAge "$kubelet_image_minimum_gc_age"
    assert_kubelet_config_value "$node" imageMaximumGCAge "$kubelet_image_maximum_gc_age"
    assert_kubelet_config_value "$node" imageGCHighThresholdPercent "$kubelet_image_gc_high_threshold"
    assert_kubelet_config_value "$node" imageGCLowThresholdPercent "$kubelet_image_gc_low_threshold"
  done
}

verify_storage_class() {
  local data
  data="$(kubectl --context "$context" get storageclass "$storage_class" -o json)" || simplematch_die "StorageClass $storage_class is missing"
  [[ "$(jq -r .provisioner <<<"$data")" == rancher.io/local-path ]] || simplematch_die 'StorageClass provisioner is not rancher.io/local-path'
  [[ "$(jq -r .volumeBindingMode <<<"$data")" == WaitForFirstConsumer ]] || simplematch_die 'StorageClass must use WaitForFirstConsumer'
  [[ "$(jq -r .reclaimPolicy <<<"$data")" == Delete ]] || simplematch_die 'StorageClass must use Delete reclaim policy'
}

verify_pv_affinity() {
  if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
    printf '%s\n' 'DRY RUN: create disposable PVC/Pod probe, verify PV node affinity, and wait for probe PV cleanup.'
    return 0
  fi

  local namespace="simplematch-kind-storage-probe-$$" pod_node pv_name pv
  local probe_run_id="kind-storage-probe-$$"
  cleanup_probe() {
    if simplematch_kind_namespace_is_disposable "$context" "$namespace" kind-manager-storage-probe; then
      simplematch_kind_delete_disposable_namespace "$context" "$namespace" 120 >/dev/null 2>&1 || true
    fi
  }
  trap cleanup_probe RETURN

  simplematch_kind_create_disposable_namespace \
    "$context" "$namespace" kind-manager-storage-probe "$probe_run_id" ||
    simplematch_die "failed to create storage verification namespace: $namespace"
  kubectl --context "$context" -n "$namespace" create serviceaccount default \
    --dry-run=client -o yaml \
    | kubectl --context "$context" apply -f - >/dev/null ||
    simplematch_die "failed to create default ServiceAccount in storage verification namespace: $namespace"
  kubectl --context "$context" -n "$namespace" wait \
    --for=jsonpath='{.metadata.name}'=default serviceaccount/default \
    --timeout=30s >/dev/null ||
    simplematch_die "default ServiceAccount did not become ready in storage verification namespace: $namespace"
  kubectl --context "$context" apply -f - >/dev/null <<EOF_PROBE
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: storage-probe
  namespace: $namespace
spec:
  accessModes: [ReadWriteOnce]
  storageClassName: $storage_class
  resources:
    requests: {storage: 1Mi}
---
apiVersion: v1
kind: Pod
metadata:
  name: storage-probe
  namespace: $namespace
spec:
  nodeSelector: {simplematch.io/worker-slot: "0"}
  containers:
    - name: pause
      image: registry.k8s.io/pause:3.10
      volumeMounts: [{name: data, mountPath: /data}]
  volumes: [{name: data, persistentVolumeClaim: {claimName: storage-probe}}]
EOF_PROBE
  kubectl --context "$context" -n "$namespace" wait --for=jsonpath='{.status.phase}'=Bound pvc/storage-probe --timeout=120s >/dev/null
  kubectl --context "$context" -n "$namespace" wait --for=condition=Ready pod/storage-probe --timeout=120s >/dev/null
  pod_node="$(kubectl --context "$context" -n "$namespace" get pod storage-probe -o jsonpath='{.spec.nodeName}')"
  pv_name="$(kubectl --context "$context" -n "$namespace" get pvc storage-probe -o jsonpath='{.spec.volumeName}')"
  pv="$(kubectl --context "$context" get pv "$pv_name" -o json)"
  jq -e --arg node "$pod_node" '.spec.nodeAffinity.required.nodeSelectorTerms[]?.matchExpressions[]? | select(.key == "kubernetes.io/hostname") | .values[] == $node' <<<"$pv" >/dev/null || simplematch_die "PV $pv_name does not preserve node affinity for $pod_node"
  trap - RETURN
  simplematch_kind_delete_disposable_namespace "$context" "$namespace" 120 >/dev/null ||
    simplematch_die 'storage verification namespace/PV cleanup did not complete'
}

verify_identity() {
  require_tools
  [[ -f "$config" && -f "$storage_manifest" ]] || simplematch_die 'kind configuration or StorageClass manifest is missing'
  exists || simplematch_die "kind cluster $cluster_name does not exist"
  verify_topology "$(nodes_json)"
  verify_kubelet_image_gc_policy
}

verify_delete_identity() {
  local node role control_planes=0
  local -a nodes
  require_tools
  exists || simplematch_die "kind cluster $cluster_name does not exist"
  mapfile -t nodes < <(kind get nodes --name "$cluster_name")
  [[ ${#nodes[@]} -gt 0 ]] || simplematch_die 'kind cluster has no discoverable nodes'
  for node in "${nodes[@]}"; do
    docker inspect "$node" >/dev/null 2>&1 || simplematch_die "missing Docker container for $node"
    [[ "$(docker inspect --format '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$node")" == "$cluster_name" ]] ||
      simplematch_die "Docker container $node is not owned by $cluster_name"
    role="$(docker inspect --format '{{index .Config.Labels "io.x-k8s.kind.role"}}' "$node")"
    case "$role" in
      control-plane) ((control_planes += 1)) ;;
      worker) ;;
      *) simplematch_die "Docker container $node has an invalid kind role: $role" ;;
    esac
  done
  [[ "$control_planes" -eq 1 ]] || simplematch_die 'cluster must have exactly one canonical control plane before deletion'
}

establish_resource_baseline() {
  local temp_baseline
  [[ "$resource_baseline_timeout" =~ ^[1-9][0-9]*$ ]] ||
    simplematch_die 'SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_TIMEOUT_SECONDS must be a positive integer'

  simplematch_local_resource_wait_clean_cluster "$cluster_name" "$resource_baseline_timeout" ||
    simplematch_die 'cluster did not become clean enough to establish a resource baseline'
  temp_baseline="$(mktemp "${TMPDIR:-/tmp}/simplematch-resource-baseline.XXXXXX.json")"
  if ! bash "$script_dir/local-resource-report.sh" \
      --cluster "$cluster_name" --no-baseline --json >"$temp_baseline"; then
    rm -f "$temp_baseline"
    simplematch_die 'failed to collect clean resource baseline after bounded snapshot retries'
  fi
  if ! simplematch_local_resource_assert_clean_baseline_json "$temp_baseline"; then
    rm -f "$temp_baseline"
    simplematch_die 'resource baseline snapshot contains run-owned Kubernetes state'
  fi
  mkdir -p "$(dirname -- "$resource_baseline_file")"
  mv "$temp_baseline" "$resource_baseline_file"
  printf 'Established clean resource baseline: %s\n' "$resource_baseline_file"
}

create_cluster() {
  [[ -f "$config" && -f "$storage_manifest" ]] || simplematch_die 'kind configuration or StorageClass manifest is missing'
  if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
    simplematch_registry_create
    simplematch_quote_command kind create cluster --config "$config"
    printf 'DRY RUN: configure kind nodes for registry %s\n' "$(simplematch_registry_endpoint)"
    simplematch_quote_command kubectl --context "$context" apply --filename "$storage_manifest"
    printf '%s\n' 'DRY RUN: verify topology, local kubelet image GC policy, local registry, StorageClass, executable PV node affinity, and clean resource baseline.'
    printf 'DRY RUN: write resource baseline %q\n' "$resource_baseline_file"
    return 0
  fi

  require_tools
  verify_docker_storage
  exists && simplematch_die "refusing to modify existing kind cluster $cluster_name"
  simplematch_registry_create
  kind create cluster --config "$config"
  simplematch_registry_connect_kind_cluster "$cluster_name"
  kubectl --context "$context" apply --filename "$storage_manifest" >/dev/null
  kubectl --context "$context" wait --for=condition=Ready nodes --all --timeout=180s >/dev/null
  verify_identity
  simplematch_registry_verify "$cluster_name"
  verify_storage_class
  verify_pv_affinity
  establish_resource_baseline
  printf 'Created and verified %s with local registry %s.\n' "$cluster_name" "$(simplematch_registry_endpoint)"
}

verify_cluster() {
  if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
    simplematch_quote_command kind get clusters
    simplematch_quote_command kubectl --context "$context" get nodes
    printf '%s\n' 'DRY RUN: verify local kubelet image GC policy, local registry, StorageClass, and executable PV node affinity.'
    return 0
  fi
  verify_identity
  simplematch_registry_verify "$cluster_name"
  verify_storage_class
  verify_pv_affinity
  printf 'Verified %s.\n' "$cluster_name"
}

delete_cluster() {
  if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
    simplematch_quote_command kind delete cluster --name "$cluster_name"
    printf '%s\n' 'DRY RUN: local registry cache and previous resource baseline are preserved; create will replace the baseline for the next cluster generation.'
    return 0
  fi
  verify_delete_identity
  kind delete cluster --name "$cluster_name"
  printf 'Deleted %s; local registry cache preserved. Previous baseline remains forensic-only until the next create replaces it.\n' "$cluster_name"
}

[[ $# -ge 1 ]] || { usage >&2; exit 2; }
command_name="$1"
shift
while (($# > 0)); do
  case "$1" in
    --dry-run) SIMPLEMATCH_DRY_RUN=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; simplematch_die "unknown option: $1" ;;
  esac
done

case "$command_name" in
  create) create_cluster ;;
  verify) verify_cluster ;;
  delete) delete_cluster ;;
  help) usage ;;
  *) usage >&2; exit 2 ;;
esac

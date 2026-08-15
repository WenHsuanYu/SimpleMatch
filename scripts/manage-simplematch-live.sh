#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cluster_name=simplematch-live
context="kind-$cluster_name"
config="$repo_root/deploy/kind/simplematch-live.yaml"
storage_manifest="$repo_root/deploy/kind/simplematch-live-storageclass.yaml"
storage_class=simplematch-rwo-pod
dry_run=false

usage() {
  cat <<'EOF'
Usage: scripts/manage-simplematch-live.sh {create|verify|delete} [--dry-run]

Manage the repository-owned canonical one-control-plane, three-worker kind lab.
Normal resilience runs reuse this cluster; delete is reserved for explicit rebuilds.
EOF
}

die() { printf 'simplematch-live: %s\n' "$*" >&2; exit 1; }

print_dry_run() {
  printf 'DRY RUN:'
  printf ' %q' "$@"
  printf '\n'
}

require_tools() {
  for tool in kind kubectl jq docker findmnt; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
  done
}

verify_docker_storage() {
  local docker_root filesystem
  docker_root="$(docker info --format '{{.DockerRootDir}}')" || die 'cannot inspect Docker root'
  [[ -n "$docker_root" ]] || die 'Docker root is empty'
  filesystem="$(findmnt -T "$docker_root" -no FSTYPE 2>/dev/null || true)"
  [[ -n "$filesystem" ]] || die "cannot determine filesystem for Docker root $docker_root"
  case "$filesystem" in
    ntfs|ntfs3|exfat|vfat|fuseblk|cifs|smb3)
      die "Docker root $docker_root uses unsupported filesystem $filesystem; move Docker data to a Linux-backed filesystem before creating kind"
      ;;
  esac
}

exists() { kind get clusters 2>/dev/null | grep -Fxq "$cluster_name"; }

nodes_json() { kubectl --context "$context" get nodes -o json; }

verify_topology() {
  local data="$1" node slot expected_role
  local -a nodes
  mapfile -t nodes < <(kind get nodes --name "$cluster_name")
  [[ ${#nodes[@]} -eq 4 ]] || die 'canonical cluster must have four kind nodes'
  [[ " ${nodes[*]} " == *" ${cluster_name}-control-plane "* ]] || die 'canonical control-plane container is missing'
  for node in "${cluster_name}-worker" "${cluster_name}-worker2" "${cluster_name}-worker3"; do
    printf '%s\n' "${nodes[@]}" | grep -Fxq "$node" || die "canonical worker container $node is missing"
  done
  [[ "$(jq '.items | length' <<<"$data")" -eq 4 ]] || die 'canonical cluster must have four Kubernetes Nodes'
  [[ "$(jq '[.items[] | select(.metadata.labels["node-role.kubernetes.io/control-plane"] == "")] | length' <<<"$data")" -eq 1 ]] || die 'canonical cluster must have one control plane'
  [[ "$(jq '[.items[] | select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience")] | length' <<<"$data")" -eq 3 ]] || die 'canonical cluster must have three local-resilience workers'
  jq -e '.items[] | select(.metadata.labels["node-role.kubernetes.io/control-plane"] == "") | any(.spec.taints[]?; .key == "node-role.kubernetes.io/control-plane" and .effect == "NoSchedule")' <<<"$data" >/dev/null || die 'control plane is not tainted NoSchedule'
  for slot in 0 1 2; do
    node="$(jq -r --arg slot "$slot" '[.items[] | select(.metadata.labels["simplematch.io/worker-slot"] == $slot) | .metadata.name] | if length == 1 then .[0] else empty end' <<<"$data")"
    [[ -n "$node" ]] || die "worker slot $slot is missing or not unique"
    jq -e --arg node "$node" '.items[] | select(.metadata.name == $node) | any(.status.conditions[]?; .type == "Ready" and .status == "True")' <<<"$data" >/dev/null || die "worker slot $slot is not Ready"
  done
  for node in "${nodes[@]}"; do
    docker inspect "$node" >/dev/null 2>&1 || die "missing Docker container for $node"
    [[ "$(docker inspect --format '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$node")" == "$cluster_name" ]] ||
      die "Docker container $node is not owned by $cluster_name"
    expected_role=worker
    [[ "$node" == "${cluster_name}-control-plane" ]] && expected_role=control-plane
    [[ "$(docker inspect --format '{{index .Config.Labels "io.x-k8s.kind.role"}}' "$node")" == "$expected_role" ]] ||
      die "Docker container $node has the wrong kind role"
  done
}

verify_storage_class() {
  local data
  data="$(kubectl --context "$context" get storageclass "$storage_class" -o json)" || die "StorageClass $storage_class is missing"
  [[ "$(jq -r .provisioner <<<"$data")" == rancher.io/local-path ]] || die 'StorageClass provisioner is not rancher.io/local-path'
  [[ "$(jq -r .volumeBindingMode <<<"$data")" == WaitForFirstConsumer ]] || die 'StorageClass must use WaitForFirstConsumer'
  [[ "$(jq -r .reclaimPolicy <<<"$data")" == Delete ]] || die 'StorageClass must use Delete reclaim policy'
}

verify_pv_affinity() {
  [[ "$dry_run" == true ]] && { printf '%s\n' 'DRY RUN: create PVC/Pod and verify PV node affinity.'; return; }
  local namespace="simplematch-kind-storage-probe-$$" pod_node pv_name pv
  cleanup_probe() { kubectl --context "$context" delete namespace "$namespace" --ignore-not-found >/dev/null 2>&1 || true; }
  trap cleanup_probe RETURN
  kubectl --context "$context" create namespace "$namespace" >/dev/null
  kubectl --context "$context" apply -f - >/dev/null <<EOF
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
EOF
  kubectl --context "$context" -n "$namespace" wait --for=jsonpath='{.status.phase}'=Bound pvc/storage-probe --timeout=120s >/dev/null
  kubectl --context "$context" -n "$namespace" wait --for=condition=Ready pod/storage-probe --timeout=120s >/dev/null
  pod_node="$(kubectl --context "$context" -n "$namespace" get pod storage-probe -o jsonpath='{.spec.nodeName}')"
  pv_name="$(kubectl --context "$context" -n "$namespace" get pvc storage-probe -o jsonpath='{.spec.volumeName}')"
  pv="$(kubectl --context "$context" get pv "$pv_name" -o json)"
  jq -e --arg node "$pod_node" '.spec.nodeAffinity.required.nodeSelectorTerms[]?.matchExpressions[]? | select(.key == "kubernetes.io/hostname") | .values[] == $node' <<<"$pv" >/dev/null || die "PV $pv_name does not preserve node affinity for $pod_node"
  trap - RETURN
  cleanup_probe
}

verify_identity() {
  require_tools
  [[ -f "$config" && -f "$storage_manifest" ]] || die 'kind configuration or StorageClass manifest is missing'
  exists || die "kind cluster $cluster_name does not exist"
  verify_topology "$(nodes_json)"
}

verify_delete_identity() {
  local node role control_planes=0
  local -a nodes
  require_tools
  exists || die "kind cluster $cluster_name does not exist"
  mapfile -t nodes < <(kind get nodes --name "$cluster_name")
  [[ ${#nodes[@]} -gt 0 ]] || die 'kind cluster has no discoverable nodes'
  for node in "${nodes[@]}"; do
    docker inspect "$node" >/dev/null 2>&1 || die "missing Docker container for $node"
    [[ "$(docker inspect --format '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$node")" == "$cluster_name" ]] ||
      die "Docker container $node is not owned by $cluster_name"
    role="$(docker inspect --format '{{index .Config.Labels "io.x-k8s.kind.role"}}' "$node")"
    case "$role" in
      control-plane) ((control_planes += 1)) ;;
      worker) ;;
      *) die "Docker container $node has an invalid kind role: $role" ;;
    esac
  done
  [[ "$control_planes" -eq 1 ]] || die 'cluster must have exactly one canonical control plane before deletion'
}

create_cluster() {
  [[ -f "$config" && -f "$storage_manifest" ]] || die 'kind configuration or StorageClass manifest is missing'
  if [[ "$dry_run" == true ]]; then
    print_dry_run kind create cluster --config "$config"
    print_dry_run kubectl --context "$context" apply --filename "$storage_manifest"
    printf '%s\n' 'DRY RUN: verify topology, StorageClass, and executable PV node affinity.'
    return
  fi
  require_tools
  verify_docker_storage
  exists && die "refusing to modify existing kind cluster $cluster_name"
  kind create cluster --config "$config"
  kubectl --context "$context" apply --filename "$storage_manifest" >/dev/null
  kubectl --context "$context" wait --for=condition=Ready nodes --all --timeout=180s >/dev/null
  verify_identity
  verify_storage_class
  verify_pv_affinity
  printf 'Created and verified %s.\n' "$cluster_name"
}

verify_cluster() {
  if [[ "$dry_run" == true ]]; then
    print_dry_run kind get clusters
    print_dry_run kubectl --context "$context" get nodes
    printf '%s\n' 'DRY RUN: verify StorageClass and executable PV node affinity.'
    return
  fi
  verify_identity
  verify_storage_class
  verify_pv_affinity
  printf 'Verified %s.\n' "$cluster_name"
}

delete_cluster() {
  if [[ "$dry_run" == true ]]; then
    print_dry_run kind delete cluster --name "$cluster_name"
    return
  fi
  verify_delete_identity
  kind delete cluster --name "$cluster_name"
  printf 'Deleted %s.\n' "$cluster_name"
}

[[ $# -ge 1 ]] || { usage >&2; exit 2; }
command="$1"
shift
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) dry_run=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

case "$command" in
  create) create_cluster ;;
  verify) verify_cluster ;;
  delete) delete_cluster ;;
  help) usage ;;
  *) usage >&2; exit 2 ;;
esac

#!/usr/bin/env bash
set -euo pipefail

# SimpleMatch local disk cleanup.
#
# Default:
#   - remove the SimpleMatch production-like Compose project, including its volumes
#   - delete disposable certification namespaces from simplematch-live
#   - prune unused containerd images inside kind nodes
#   - prune Docker build cache older than 24h
#
# Optional:
#   --delete-cluster  Delete the entire simplematch-live kind cluster for maximum reclamation.
#   --aggressive      Also prune unused Docker images and anonymous/unused Docker volumes globally.
#                     This is intentionally opt-in because those resources may belong to other projects.
#   --dry-run         Print commands without executing them.

COMPOSE_PROJECT="${SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT:-simplematch-local-production-like}"
KIND_CLUSTER="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
KIND_CONTEXT="kind-${KIND_CLUSTER}"
CERT_NAMESPACE_PREFIX="${SIMPLEMATCH_CERTIFICATION_NAMESPACE_PREFIX:-simplematch-local-cert-}"
BUILDER_CACHE_UNTIL="${SIMPLEMATCH_BUILDER_CACHE_UNTIL:-24h}"

delete_cluster=false
aggressive=false
dry_run=false

usage() {
  cat <<'EOF'
Usage:
  scripts/clean-local-disk.sh [options]

Options:
  --delete-cluster  Delete the entire simplematch-live kind cluster.
  --aggressive      Additionally prune globally unused Docker images and volumes.
  --dry-run         Print commands without changing anything.
  -h, --help        Show this help.

Environment:
  SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT
      Compose project name. Default: simplematch-local-production-like

  SIMPLEMATCH_KIND_CLUSTER_NAME
      kind cluster name. Default: simplematch-live

  SIMPLEMATCH_CERTIFICATION_NAMESPACE_PREFIX
      Disposable namespace prefix. Default: simplematch-local-cert-

  SIMPLEMATCH_BUILDER_CACHE_UNTIL
      Age threshold for Docker builder cache. Default: 24h

Default cleanup deliberately does NOT run `docker system prune -a` and does NOT
delete the reusable kind cluster. Use --delete-cluster when the cluster itself is
no longer needed.
EOF
}

log() {
  printf '==> %s\n' "$*"
}

warn() {
  printf 'WARN: %s\n' "$*" >&2
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

run() {
  if [[ "$dry_run" == true ]]; then
    printf 'DRY RUN:'
    printf ' %q' "$@"
    printf '\n'
    return 0
  fi
  "$@"
}

have() {
  command -v "$1" >/dev/null 2>&1
}

docker_disk_usage() {
  if ! have docker; then
    return 0
  fi
  log "Docker disk usage"
  docker system df || true
}

compose_down() {
  local compose=(docker compose)

  if ! have docker; then
    die "docker is required."
  fi

  if ! docker info >/dev/null 2>&1; then
    die "Docker daemon is not reachable."
  fi

  if ! docker compose version >/dev/null 2>&1; then
    if have docker-compose; then
      compose=(docker-compose)
    else
      die "Docker Compose v2 or docker-compose is required."
    fi
  fi

  # Project-name-only teardown is intentional. Compose can identify containers,
  # networks, and named project resources through Docker Compose labels even if
  # this cleanup script is invoked outside the repository root.
  log "Removing Compose project: ${COMPOSE_PROJECT}"
  run "${compose[@]}" \
    --project-name "$COMPOSE_PROJECT" \
    down \
    --volumes \
    --remove-orphans \
    --timeout 30

  # The production-like compose network has an explicitly stable name in the
  # repository. Normally `compose down` removes it; this handles stale networks
  # left by interrupted runs. Refuse to remove a network that still has endpoints.
  local network_name="${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK:-simplematch-production-like}"
  if docker network inspect "$network_name" >/dev/null 2>&1; then
    local attached
    attached="$(docker network inspect \
      --format '{{len .Containers}}' "$network_name" 2>/dev/null || printf 'unknown')"
    if [[ "$attached" == "0" ]]; then
      log "Removing stale Compose network: ${network_name}"
      run docker network rm "$network_name"
    else
      warn "Keeping network ${network_name}; attached endpoints: ${attached}"
    fi
  fi
}

kind_cluster_exists() {
  have kind && kind get clusters 2>/dev/null | grep -Fxq "$KIND_CLUSTER"
}

delete_disposable_namespaces() {
  if ! kind_cluster_exists; then
    log "kind cluster ${KIND_CLUSTER} does not exist; skipping Kubernetes cleanup."
    return 0
  fi

  if ! have kubectl; then
    warn "kubectl is not installed; skipping disposable namespace cleanup."
    return 0
  fi

  log "Deleting disposable namespaces in ${KIND_CONTEXT} with prefix ${CERT_NAMESPACE_PREFIX}"

  local namespaces
  namespaces="$(
    kubectl --context "$KIND_CONTEXT" get namespaces \
      -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null |
      awk -v prefix="$CERT_NAMESPACE_PREFIX" 'index($0, prefix) == 1'
  )"

  if [[ -z "$namespaces" ]]; then
    log "No disposable certification namespaces found."
    return 0
  fi

  while IFS= read -r namespace; do
    [[ -n "$namespace" ]] || continue
    run kubectl --context "$KIND_CONTEXT" delete namespace \
      "$namespace" \
      --ignore-not-found \
      --wait=false
  done <<<"$namespaces"
}

prune_kind_node_images() {
  if ! kind_cluster_exists; then
    return 0
  fi

  local nodes
  nodes="$(kind get nodes --name "$KIND_CLUSTER" 2>/dev/null || true)"
  if [[ -z "$nodes" ]]; then
    warn "No nodes found for kind cluster ${KIND_CLUSTER}."
    return 0
  fi

  log "Pruning unused containerd images inside ${KIND_CLUSTER}"

  while IFS= read -r node; do
    [[ -n "$node" ]] || continue

    # kind node images include crictl. `crictl rmi --prune` removes images not
    # currently used by containers; running workloads remain intact. Future Pods
    # may need to pull/reload an image again.
    if [[ "$dry_run" == true ]]; then
      printf 'DRY RUN: docker exec %q crictl rmi --prune\n' "$node"
      continue
    fi

    if ! docker exec "$node" crictl rmi --prune; then
      warn "Image prune failed on ${node}; continuing."
    fi
  done <<<"$nodes"
}

delete_kind_cluster() {
  if ! kind_cluster_exists; then
    log "kind cluster ${KIND_CLUSTER} does not exist."
    return 0
  fi

  log "Deleting kind cluster: ${KIND_CLUSTER}"
  run kind delete cluster --name "$KIND_CLUSTER"
}

prune_builder_cache() {
  log "Pruning Docker builder cache older than ${BUILDER_CACHE_UNTIL}"
  run docker builder prune \
    --force \
    --filter "until=${BUILDER_CACHE_UNTIL}"
}

aggressive_docker_prune() {
  [[ "$aggressive" == true ]] || return 0

  warn "Aggressive cleanup is enabled; globally unused Docker resources may belong to other projects."

  log "Pruning globally unused Docker images"
  run docker image prune --all --force

  log "Pruning globally unused Docker volumes"
  run docker volume prune --force
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --delete-cluster)
      delete_cluster=true
      shift
      ;;
    --aggressive)
      aggressive=true
      shift
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "Unknown option: $1"
      ;;
  esac
done

have docker || die "docker is required."
docker info >/dev/null 2>&1 || die "Docker daemon is not reachable."

if [[ "$delete_cluster" == true ]]; then
  have kind || die "kind is required by --delete-cluster."
fi

printf '\n'
docker_disk_usage
printf '\n'

compose_down

if [[ "$delete_cluster" == true ]]; then
  delete_kind_cluster
else
  delete_disposable_namespaces
  prune_kind_node_images
fi

prune_builder_cache
aggressive_docker_prune

printf '\n'
docker_disk_usage
printf '\n'
log "Cleanup complete."

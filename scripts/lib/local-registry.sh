#!/usr/bin/env bash

# SimpleMatch local OCI registry primitives.
# This follows kind's documented localhost registry pattern: the host pushes to
# localhost:<port>, while each kind node aliases that endpoint to the registry
# container through containerd hosts.toml.

SIMPLEMATCH_LOCAL_REGISTRY_NAME="${SIMPLEMATCH_LOCAL_REGISTRY_NAME:-simplematch-local-registry}"
SIMPLEMATCH_LOCAL_REGISTRY_IMAGE="${SIMPLEMATCH_LOCAL_REGISTRY_IMAGE:-registry:3}"
SIMPLEMATCH_LOCAL_REGISTRY_HOST="${SIMPLEMATCH_LOCAL_REGISTRY_HOST:-localhost}"
SIMPLEMATCH_LOCAL_REGISTRY_PORT="${SIMPLEMATCH_LOCAL_REGISTRY_PORT:-5001}"
SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME="${SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME:-simplematch-local-registry-data}"
SIMPLEMATCH_LOCAL_REGISTRY_NETWORK="${SIMPLEMATCH_LOCAL_REGISTRY_NETWORK:-kind}"

simplematch_registry_endpoint() {
  printf '%s:%s\n' "$SIMPLEMATCH_LOCAL_REGISTRY_HOST" "$SIMPLEMATCH_LOCAL_REGISTRY_PORT"
}

simplematch_registry_container_exists() {
  docker inspect "$SIMPLEMATCH_LOCAL_REGISTRY_NAME" >/dev/null 2>&1
}

simplematch_registry_container_running() {
  [[ "$(docker inspect --format '{{.State.Running}}' "$SIMPLEMATCH_LOCAL_REGISTRY_NAME" 2>/dev/null || true)" == true ]]
}

simplematch_registry_create() {
  if [[ "${SIMPLEMATCH_DRY_RUN:-false}" == true ]]; then
    simplematch_quote_command docker volume create "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME"
    simplematch_quote_command docker run \
      --detach \
      --restart=unless-stopped \
      --publish "127.0.0.1:${SIMPLEMATCH_LOCAL_REGISTRY_PORT}:5000" \
      --volume "${SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME}:/var/lib/registry" \
      --name "$SIMPLEMATCH_LOCAL_REGISTRY_NAME" \
      "$SIMPLEMATCH_LOCAL_REGISTRY_IMAGE"
    return 0
  fi

  simplematch_require_command docker
  docker info >/dev/null 2>&1 || simplematch_die 'Docker daemon is not reachable'

  if simplematch_registry_container_running; then
    simplematch_info "Local registry already running: $SIMPLEMATCH_LOCAL_REGISTRY_NAME"
    return 0
  fi

  if simplematch_registry_container_exists; then
    simplematch_log "Start local registry $SIMPLEMATCH_LOCAL_REGISTRY_NAME"
    simplematch_run docker start "$SIMPLEMATCH_LOCAL_REGISTRY_NAME"
    return 0
  fi

  simplematch_log "Create local registry $SIMPLEMATCH_LOCAL_REGISTRY_NAME"
  simplematch_run docker volume create "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME" >/dev/null
  simplematch_run docker run \
    --detach \
    --restart=unless-stopped \
    --publish "127.0.0.1:${SIMPLEMATCH_LOCAL_REGISTRY_PORT}:5000" \
    --volume "${SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME}:/var/lib/registry" \
    --name "$SIMPLEMATCH_LOCAL_REGISTRY_NAME" \
    "$SIMPLEMATCH_LOCAL_REGISTRY_IMAGE"
}

simplematch_registry_connect_kind_cluster() {
  local cluster_name="$1"
  local context="kind-${cluster_name}"
  local registry_dir="/etc/containerd/certs.d/$(simplematch_registry_endpoint)"
  local node
  local nodes

  simplematch_require_command kind
  simplematch_require_command kubectl
  simplematch_registry_create

  kind get clusters 2>/dev/null | grep -Fxq "$cluster_name" ||
    simplematch_die "kind cluster does not exist: $cluster_name"

  docker network inspect "$SIMPLEMATCH_LOCAL_REGISTRY_NETWORK" >/dev/null 2>&1 ||
    simplematch_die "Docker network does not exist: $SIMPLEMATCH_LOCAL_REGISTRY_NETWORK"

  if [[ "$(docker inspect --format "{{json .NetworkSettings.Networks.${SIMPLEMATCH_LOCAL_REGISTRY_NETWORK}}}" "$SIMPLEMATCH_LOCAL_REGISTRY_NAME" 2>/dev/null || true)" == null ]]; then
    simplematch_log "Connect registry to Docker network $SIMPLEMATCH_LOCAL_REGISTRY_NETWORK"
    simplematch_run docker network connect "$SIMPLEMATCH_LOCAL_REGISTRY_NETWORK" "$SIMPLEMATCH_LOCAL_REGISTRY_NAME"
  fi

  nodes="$(kind get nodes --name "$cluster_name")"
  [[ -n "$nodes" ]] || simplematch_die "kind cluster has no nodes: $cluster_name"

  simplematch_log "Configure containerd registry alias on $cluster_name"
  while IFS= read -r node; do
    [[ -n "$node" ]] || continue
    simplematch_run docker exec "$node" mkdir -p "$registry_dir"
    if [[ "${SIMPLEMATCH_DRY_RUN:-false}" == true ]]; then
      printf 'DRY RUN: write %s/hosts.toml on %s -> http://%s:5000\n' \
        "$registry_dir" "$node" "$SIMPLEMATCH_LOCAL_REGISTRY_NAME"
      continue
    fi
    cat <<EOF_HOSTS | docker exec -i "$node" sh -c "cat > '$registry_dir/hosts.toml'"
[host."http://${SIMPLEMATCH_LOCAL_REGISTRY_NAME}:5000"]
  capabilities = ["pull", "resolve"]
EOF_HOSTS
  done <<<"$nodes"

  if [[ "${SIMPLEMATCH_DRY_RUN:-false}" == true ]]; then
    printf 'DRY RUN: apply kube-public/local-registry-hosting for %s\n' "$(simplematch_registry_endpoint)"
    return 0
  fi

  kubectl --context "$context" apply -f - >/dev/null <<EOF_CONFIGMAP
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "$(simplematch_registry_endpoint)"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF_CONFIGMAP
}

simplematch_registry_verify() {
  local cluster_name="${1:-}"
  local endpoint
  local registry_dir
  local node
  local nodes

  simplematch_require_command docker
  simplematch_registry_container_running ||
    simplematch_die "local registry is not running: $SIMPLEMATCH_LOCAL_REGISTRY_NAME"

  endpoint="$(simplematch_registry_endpoint)"
  docker volume inspect "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME" >/dev/null 2>&1 ||
    simplematch_die "local registry data volume is missing: $SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME"

  if [[ -z "$cluster_name" ]]; then
    simplematch_info "Verified local registry: $endpoint"
    return 0
  fi

  simplematch_require_command kind
  simplematch_require_command kubectl
  kind get clusters 2>/dev/null | grep -Fxq "$cluster_name" ||
    simplematch_die "kind cluster does not exist: $cluster_name"

  [[ "$(docker inspect --format "{{json .NetworkSettings.Networks.${SIMPLEMATCH_LOCAL_REGISTRY_NETWORK}}}" "$SIMPLEMATCH_LOCAL_REGISTRY_NAME" 2>/dev/null || true)" != null ]] ||
    simplematch_die "registry is not connected to Docker network $SIMPLEMATCH_LOCAL_REGISTRY_NETWORK"

  registry_dir="/etc/containerd/certs.d/$endpoint"
  nodes="$(kind get nodes --name "$cluster_name")"
  while IFS= read -r node; do
    [[ -n "$node" ]] || continue
    docker exec "$node" test -f "$registry_dir/hosts.toml" ||
      simplematch_die "registry hosts.toml is missing on $node"
    docker exec "$node" grep -Fq "http://${SIMPLEMATCH_LOCAL_REGISTRY_NAME}:5000" "$registry_dir/hosts.toml" ||
      simplematch_die "registry alias is wrong on $node"
  done <<<"$nodes"

  kubectl --context "kind-${cluster_name}" -n kube-public get configmap local-registry-hosting >/dev/null 2>&1 ||
    simplematch_die 'kube-public/local-registry-hosting is missing'

  simplematch_info "Verified local registry integration for $cluster_name: $endpoint"
}

simplematch_registry_delete() {
  local remove_data="${1:-false}"

  if [[ "${SIMPLEMATCH_DRY_RUN:-false}" == true ]]; then
    simplematch_quote_command docker rm --force "$SIMPLEMATCH_LOCAL_REGISTRY_NAME"
    if [[ "$remove_data" == true ]]; then
      simplematch_quote_command docker volume rm "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME"
    fi
    return 0
  fi

  simplematch_require_command docker
  if simplematch_registry_container_exists; then
    simplematch_log "Remove local registry container $SIMPLEMATCH_LOCAL_REGISTRY_NAME"
    simplematch_run docker rm --force "$SIMPLEMATCH_LOCAL_REGISTRY_NAME"
  fi

  if [[ "$remove_data" == true ]] && docker volume inspect "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME" >/dev/null 2>&1; then
    simplematch_log "Remove local registry data volume $SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME"
    simplematch_run docker volume rm "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME"
  fi
}

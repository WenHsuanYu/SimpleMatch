#!/usr/bin/env bash

simplematch_kind_exists() {
  local cluster_name="$1"
  command -v kind >/dev/null 2>&1 &&
    kind get clusters 2>/dev/null | grep -Fxq "$cluster_name"
}

simplematch_kind_nodes() {
  local cluster_name="$1"
  kind get nodes --name "$cluster_name" 2>/dev/null
}

simplematch_kind_create_disposable_namespace() {
  local context="$1"
  local namespace="$2"
  local managed_by="$3"
  local run_id="$4"
  shift 4
  local -a labels=(
    simplematch.io/lifecycle=disposable
    "simplematch.io/managed-by=${managed_by}"
    "simplematch.io/run-id=${run_id}"
  )
  labels+=("$@")

  simplematch_require_command kubectl
  if kubectl --context "$context" get namespace "$namespace" >/dev/null 2>&1; then
    simplematch_warn "namespace already exists: $namespace"
    return 1
  fi

  if [[ "${SIMPLEMATCH_DRY_RUN:-false}" == true ]]; then
    simplematch_quote_command kubectl --context "$context" create namespace "$namespace"
    simplematch_quote_command kubectl --context "$context" label namespace "$namespace" \
      "${labels[@]}"
    return 0
  fi

  kubectl --context "$context" create namespace "$namespace" >/dev/null
  if ! kubectl --context "$context" label namespace "$namespace" \
      "${labels[@]}" >/dev/null; then
    simplematch_warn "failed to establish disposable ownership labels on namespace $namespace; removing it"
    kubectl --context "$context" delete namespace "$namespace" \
      --ignore-not-found --wait=true --timeout=120s >/dev/null 2>&1 || true
    return 1
  fi
}

simplematch_kind_namespace_is_disposable() {
  local context="$1"
  local namespace="$2"
  local expected_manager="${3:-}"
  local lifecycle
  local managed_by

  lifecycle="$(
    kubectl --context "$context" get namespace "$namespace" \
      -o jsonpath='{.metadata.labels.simplematch\.io/lifecycle}' 2>/dev/null || true
  )"
  [[ "$lifecycle" == disposable ]] || return 1

  if [[ -n "$expected_manager" ]]; then
    managed_by="$(
      kubectl --context "$context" get namespace "$namespace" \
        -o jsonpath='{.metadata.labels.simplematch\.io/managed-by}' 2>/dev/null || true
    )"
    [[ "$managed_by" == "$expected_manager" ]] || return 1
  fi
}

simplematch_kind_claim_namespaces() {
  local context="$1"

  kubectl --context "$context" get pv \
    -o jsonpath='{range .items[*]}{.spec.claimRef.namespace}{"\n"}{end}' \
    2>/dev/null
}

simplematch_kind_wait_claim_pvs_gone() {
  local context="$1"
  local namespace="$2"
  local timeout_seconds="$3"
  local deadline=$((SECONDS + timeout_seconds))
  local claim_namespaces

  while ((SECONDS < deadline)); do
    if ! claim_namespaces="$(simplematch_kind_claim_namespaces "$context")"; then
      sleep 1
      continue
    fi
    if ! grep -Fxq "$namespace" <<<"$claim_namespaces"; then
      return 0
    fi
    sleep 1
  done

  simplematch_warn "PV cleanup could not be confirmed within ${timeout_seconds}s for namespace $namespace"
  return 1
}

simplematch_kind_delete_disposable_namespace() {
  local context="$1"
  local namespace="$2"
  local timeout_seconds="${3:-180}"

  if ! simplematch_kind_namespace_is_disposable "$context" "$namespace"; then
    simplematch_warn "refusing to delete namespace without simplematch.io/lifecycle=disposable: $namespace"
    return 1
  fi

  simplematch_log "Delete disposable namespace $namespace"
  if [[ "${SIMPLEMATCH_DRY_RUN:-false}" == true ]]; then
    simplematch_quote_command kubectl --context "$context" delete namespace "$namespace" \
      --ignore-not-found --wait=true --timeout="${timeout_seconds}s"
    return 0
  fi

  kubectl --context "$context" delete namespace "$namespace" \
    --ignore-not-found --wait=true --timeout="${timeout_seconds}s" || return 1
  simplematch_kind_wait_claim_pvs_gone "$context" "$namespace" "$timeout_seconds"
}

simplematch_kind_disposable_namespaces() {
  local context="$1"

  kubectl --context "$context" get namespaces \
    -l simplematch.io/lifecycle=disposable \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' \
    2>/dev/null
}

simplematch_kind_delete_disposable_namespaces() {
  local cluster_name="$1"
  local timeout_seconds="${2:-180}"
  local context="kind-${cluster_name}"
  local namespace
  local namespaces
  local cleanup_failed=false

  simplematch_require_command kubectl
  if ! simplematch_kind_exists "$cluster_name"; then
    simplematch_info "kind cluster does not exist; skipping namespace cleanup: $cluster_name"
    return 0
  fi

  if ! namespaces="$(simplematch_kind_disposable_namespaces "$context")"; then
    simplematch_warn "failed to list lifecycle-labeled disposable namespaces in $context"
    return 1
  fi
  if [[ -z "$namespaces" ]]; then
    simplematch_info 'No lifecycle-labeled disposable SimpleMatch namespaces found.'
    return 0
  fi

  while IFS= read -r namespace; do
    [[ -n "$namespace" ]] || continue
    simplematch_kind_delete_disposable_namespace \
      "$context" "$namespace" "$timeout_seconds" || cleanup_failed=true
  done <<<"$namespaces"

  [[ "$cleanup_failed" == false ]]
}

simplematch_kind_prune_unused_images() {
  local cluster_name="$1"
  local node
  local nodes

  if ! simplematch_kind_exists "$cluster_name"; then
    simplematch_info "kind cluster does not exist; skipping node image prune: $cluster_name"
    return 0
  fi

  nodes="$(simplematch_kind_nodes "$cluster_name")"
  while IFS= read -r node; do
    [[ -n "$node" ]] || continue
    simplematch_info "Pruning unused CRI images on $node"
    if [[ "${SIMPLEMATCH_DRY_RUN:-false}" == true ]]; then
      simplematch_quote_command docker exec "$node" crictl rmi --prune
    elif ! docker exec "$node" crictl rmi --prune; then
      simplematch_warn "CRI image prune failed on $node"
    fi
  done <<<"$nodes"
}

simplematch_kind_resource_report() {
  local cluster_name="$1"
  local node
  local nodes
  local exited_count
  local notready_count

  if ! simplematch_kind_exists "$cluster_name"; then
    simplematch_info "kind cluster absent: $cluster_name"
    return 0
  fi

  nodes="$(simplematch_kind_nodes "$cluster_name")"
  while IFS= read -r node; do
    [[ -n "$node" ]] || continue
    printf '\n--- %s ---\n' "$node"
    docker exec "$node" sh -c \
      'du -xhd1 /var/lib/containerd 2>/dev/null | sort -h' || true
    exited_count="$(docker exec "$node" crictl ps -a --state Exited -q 2>/dev/null | sed '/^$/d' | wc -l)"
    notready_count="$(docker exec "$node" crictl pods --state NotReady -q 2>/dev/null | sed '/^$/d' | wc -l)"
    printf 'exited_containers=%s notready_sandboxes=%s\n' "$exited_count" "$notready_count"
  done <<<"$nodes"
}

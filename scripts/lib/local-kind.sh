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

: "${SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_DEFAULT_SECONDS:=60}"
: "${SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_MAX_SECONDS:=120}"

_simplematch_kind_image_cache_remaining() {
  local deadline_at="$1"
  local remaining=$((deadline_at - SECONDS))

  (( remaining > 0 )) || return 124
  printf '%s\n' "$remaining"
}

_simplematch_kind_image_cache_run() {
  local deadline_at="$1"
  shift
  local remaining

  remaining="$(_simplematch_kind_image_cache_remaining "$deadline_at")" || return 124
  timeout --foreground "${remaining}s" "$@"
}

# Verify the deployed workload image against every eligible kind node before
# it can trigger a cold pull. The check deliberately does not pull: a missing
# or unexecutable image is a precondition failure, not a recovery event. The
# evidence file is written for both PASS and FAIL so a caller can explain the
# exact node-level failure without mutating the cluster.
simplematch_kind_image_cache_preflight() {
  local context="$1"
  local workload_file="$2"
  local evidence_file="$3"
  local budget_seconds="$SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_DEFAULT_SECONDS"
  local started_at completed_at deadline_at nodes_json image_reference node_selector
  local expected_identity="" identity_source="node-containerd" reference_digest=""
  local status=PASS failure_reason="" results='[]'
  local node inspect_output identity probe_name node_status reason
  local inspect_status probe_status
  local -a nodes=()

  [[ -n "$context" && -s "$workload_file" && -n "$evidence_file" ]] || return 1
  [[ "$budget_seconds" =~ ^[1-9][0-9]*$ &&
    "$budget_seconds" -le "$SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_MAX_SECONDS" ]] || return 1
  command -v kubectl >/dev/null 2>&1 || return 1
  command -v docker >/dev/null 2>&1 || return 1
  command -v jq >/dev/null 2>&1 || return 1
  command -v timeout >/dev/null 2>&1 || return 1

  mkdir -p "$(dirname -- "$evidence_file")" || return 1
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || return 1
  deadline_at=$((SECONDS + budget_seconds))
  image_reference="$(jq -er '
    [.spec.template.spec.containers[]? | select(.name == "kafka-connect") | .image]
    | if length == 1 and (.[0] | type == "string" and length > 0) then .[0]
      else empty end
  ' "$workload_file")" || {
    status=FAILED
    failure_reason='Connect workload does not identify exactly one runtime image'
  }
  [[ "$image_reference" != *[[:space:]]* ]] || {
    status=FAILED
    failure_reason='Connect workload image reference contains whitespace'
  }
  node_selector="$(jq -c '.spec.template.spec.nodeSelector // {}' "$workload_file")" || {
    status=FAILED
    failure_reason='Connect workload node selector is malformed'
  }
  if [[ "$image_reference" == *@sha256:* ]]; then
    reference_digest="${image_reference##*@}"
    [[ "$reference_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || {
      failure_reason='digest-pinned image reference is not canonical'
      status=FAILED
    }
  fi

  if [[ "$status" == PASS ]]; then
    if ! nodes_json="$(_simplematch_kind_image_cache_run "$deadline_at" \
        kubectl --context "$context" get nodes -o json 2>&1)"; then
      status=FAILED
      failure_reason='could not read eligible kind nodes before the image-cache deadline'
    elif ! jq -e '.items | type == "array"' <<<"$nodes_json" >/dev/null 2>&1; then
      status=FAILED
      failure_reason='kind node evidence is malformed'
    else
      mapfile -t nodes < <(jq -r --argjson selector "$node_selector" '
        .items[]
        | select(.spec.unschedulable != true)
        | select(([.spec.taints[]?.effect] | index("NoSchedule")) == null)
        | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
        | select(. as $node
            | ($selector | to_entries | all(.[];
                ($node.metadata.labels[.key] // null) == .value)))
        | .metadata.name
      ' <<<"$nodes_json")
      if ((${#nodes[@]} == 0)); then
        status=FAILED
        failure_reason='no schedulable kind worker is available for the image-cache preflight'
      fi
    fi
  fi

  if [[ "$status" == PASS ]]; then
    for node in "${nodes[@]}"; do
      node_status=PASS
      reason=''
      identity=''
      inspect_status=FAILED
      probe_status=NOT_RUN
      inspect_output=''
      if inspect_output="$(_simplematch_kind_image_cache_run "$deadline_at" \
          docker exec "$node" crictl inspecti "$image_reference" 2>&1)" &&
        identity="$(jq -er '.status.id | select(type == "string" and test("^sha256:[0-9a-f]{64}$"))' \
          <<<"$inspect_output" 2>/dev/null)"; then
        inspect_status=PASS
      else
        node_status=FAILED
        reason='image is not present with a canonical identity in node containerd'
      fi

      if [[ "$node_status" == PASS && -n "$expected_identity" &&
        "$identity" != "$expected_identity" ]]; then
        node_status=FAILED
        reason="image identity differs from the expected $expected_identity"
      elif [[ "$node_status" == PASS && -z "$expected_identity" ]]; then
        expected_identity="$identity"
      fi

      if [[ "$node_status" == PASS ]]; then
        probe_name="simplematch-image-cache-probe-${RANDOM}-${BASHPID}"
        if _simplematch_kind_image_cache_run "$deadline_at" \
            docker exec "$node" ctr -n k8s.io run --rm --net-host \
            "$image_reference" "$probe_name" /bin/sh -c true \
            >/dev/null 2>&1; then
          probe_status=PASS
        else
          node_status=FAILED
          probe_status=FAILED
          reason='image metadata exists but containerd execution probe failed'
        fi
      fi

      results="$(jq -c --arg node "$node" --arg status "$node_status" \
        --arg inspect "$inspect_status" --arg probe "$probe_status" \
        --arg identity "$identity" --arg reason "$reason" \
        '. + [{node:$node,status:$status,inspect_status:$inspect,
          execution_probe_status:$probe,identity:$identity,
          failure_reason:(if $reason == "" then null else $reason end)}]' \
        <<<"$results")" || return 1
      [[ "$node_status" == PASS ]] || status=FAILED

      if ! _simplematch_kind_image_cache_remaining "$deadline_at" >/dev/null; then
        status=FAILED
        failure_reason='image-cache preflight deadline elapsed before every eligible node was checked'
        break
      fi
    done
  fi

  if [[ "$status" == FAILED && -z "$failure_reason" ]]; then
    failure_reason='one or more eligible kind nodes failed the image-cache preflight'
  fi
  completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || return 1
  if [[ "$status" == PASS && -z "$expected_identity" ]]; then
    status=FAILED
    failure_reason='image-cache preflight did not observe an image identity'
  fi
  jq -n --arg status "$status" --arg context "$context" \
    --arg image "$image_reference" --arg identity "$expected_identity" \
    --arg source "$identity_source" --arg started "$started_at" \
    --arg completed "$completed_at" --argjson budget "$budget_seconds" \
    --argjson nodes "$results" --arg reason "$failure_reason" \
    '{schema_version:1,status:$status,context:$context,
      image_reference:$image,image_identity:(if $identity == "" then null else $identity end),
      identity_source:$source,budget_seconds:$budget,
      started_at_utc:$started,completed_at_utc:$completed,nodes:$nodes,
      failure_reason:(if $reason == "" then null else $reason end)}' \
    >"$evidence_file" || return 1
  [[ "$status" == PASS ]]
}

simplematch_kind_node_readiness_state() {
  local node_json="$1"

  jq -er '
    .status.conditions // error("Ready condition is missing")
    | map(select(.type == "Ready"))
    | if length != 1 then error("Ready condition is ambiguous")
      elif .[0].status == "True" then "true"
      elif .[0].status == "False" then "false"
      elif .[0].status == "Unknown" then "unknown"
      else error("Ready condition has an unsupported status")
      end
  ' <<<"$node_json"
}

_simplematch_kind_control_plane_snapshot() {
  local context="$1" command_timeout_seconds="$2"

  timeout --foreground "${command_timeout_seconds}s" \
    kubectl --context "$context" get pods -n kube-system -o json | jq -c '
      [.items[]
       | select((.metadata.name // "") | test("^(etcd-|kube-controller-manager-|kube-scheduler-)"))
       | {name:.metadata.name,
          phase:(.status.phase // ""),
          ready:any(.status.conditions[]?; .type == "Ready" and .status == "True"),
          restart_count:([.status.containerStatuses[]?.restartCount] | add // 0)}]
      | sort_by(.name)'
}

_simplematch_kind_control_plane_timeout() {
  local deadline_at="$1" command_timeout_seconds="$2"
  local remaining timeout_seconds

  remaining=$((deadline_at - SECONDS))
  (( remaining > 0 )) || return 124
  timeout_seconds="$command_timeout_seconds"
  (( remaining < timeout_seconds )) && timeout_seconds="$remaining"
  printf '%s\n' "$timeout_seconds"
}

# Verify that the kind control plane is stable before a state-changing
# diagnostic. The check is shared by dependency and Connect worker-loss
# diagnostics so a fault cannot be attributed to an unstable etcd or lease
# path. If an evidence directory is supplied, the raw readiness, snapshots,
# and recent events are retained for the diagnostic report. The optional fifth
# argument is one aggregate budget for the whole check, not a multiplier for
# each kubectl call.
simplematch_kind_validate_control_plane_stability() {
  local context="$1"
  local window_seconds="${2:-5}"
  local command_timeout_seconds="${3:-60}"
  local evidence_dir="${4:-}"
  local aggregate_timeout_seconds="${5:-$((window_seconds + command_timeout_seconds * 4))}"
  local before after events now readyz deadline_at command_timeout

  [[ "$window_seconds" =~ ^[1-9][0-9]*$ ]] || return 1
  [[ "$command_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || return 1
  [[ "$aggregate_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || return 1
  deadline_at=$((SECONDS + aggregate_timeout_seconds))
  if [[ -n "$evidence_dir" ]]; then
    mkdir -p "$evidence_dir" || return 1
  fi

  command_timeout="$(_simplematch_kind_control_plane_timeout "$deadline_at" \
    "$command_timeout_seconds")" || {
    printf 'kind control plane stability deadline elapsed before readyz for %s\n' \
      "$context" >&2
    return 1
  }
  readyz="$(timeout --foreground "${command_timeout}s" \
    kubectl --context "$context" get --raw='/readyz?verbose')" || {
    printf 'kind control plane readyz check failed for %s\n' "$context" >&2
    return 1
  }
  grep -Fq 'readyz check passed' <<<"$readyz" || {
    printf 'kind control plane is not reporting readyz success for %s\n' "$context" >&2
    return 1
  }
  if [[ -n "$evidence_dir" ]]; then
    printf '%s\n' "$readyz" >"$evidence_dir/readyz.txt" || return 1
  fi

  command_timeout="$(_simplematch_kind_control_plane_timeout "$deadline_at" \
    "$command_timeout_seconds")" || {
    printf 'kind control plane stability deadline elapsed before snapshot for %s\n' \
      "$context" >&2
    return 1
  }
  before="$(_simplematch_kind_control_plane_snapshot \
    "$context" "$command_timeout")" || {
    printf 'could not capture kind control-plane readiness for %s\n' "$context" >&2
    return 1
  }
  jq -e 'length == 3 and all(.[]; .phase == "Running" and .ready == true)' \
    <<<"$before" >/dev/null || {
    printf 'kind control-plane components are not all Ready for %s\n' "$context" >&2
    return 1
  }
  if [[ -n "$evidence_dir" ]]; then
    printf '%s\n' "$before" >"$evidence_dir/before.json" || return 1
  fi

  command_timeout="$(_simplematch_kind_control_plane_timeout "$deadline_at" \
    "$((window_seconds + 1))")" || {
    printf 'kind control plane stability deadline elapsed before window for %s\n' \
      "$context" >&2
    return 1
  }
  timeout --foreground "${command_timeout}s" sleep "$window_seconds" || {
    printf 'kind control-plane stability window timed out for %s\n' "$context" >&2
    return 1
  }
  command_timeout="$(_simplematch_kind_control_plane_timeout "$deadline_at" \
    "$command_timeout_seconds")" || {
    printf 'kind control plane stability deadline elapsed after window for %s\n' \
      "$context" >&2
    return 1
  }
  after="$(_simplematch_kind_control_plane_snapshot \
    "$context" "$command_timeout")" || {
    printf 'could not capture kind control-plane readiness after stability window for %s\n' \
      "$context" >&2
    return 1
  }
  jq -n -e --argjson before "$before" --argjson after "$after" \
    '$before == $after' >/dev/null || {
    printf 'kind control-plane readiness or restart counts changed for %s\n' "$context" >&2
    return 1
  }
  if [[ -n "$evidence_dir" ]]; then
    printf '%s\n' "$after" >"$evidence_dir/after.json" || return 1
  fi

  command_timeout="$(_simplematch_kind_control_plane_timeout "$deadline_at" \
    "$command_timeout_seconds")" || {
    printf 'kind control plane stability deadline elapsed before events for %s\n' \
      "$context" >&2
    return 1
  }
  events="$(timeout --foreground "${command_timeout}s" \
    kubectl --context "$context" get events -n kube-system --sort-by=.lastTimestamp -o json)" || {
    printf 'could not inspect recent kind control-plane events for %s\n' "$context" >&2
    return 1
  }
  if [[ -n "$evidence_dir" ]]; then
    printf '%s\n' "$events" >"$evidence_dir/events.json" || return 1
  fi
  now="$(date -u +%s)" || return 1
  jq -e --argjson now "$now" --argjson window "$window_seconds" '
    def event_epoch:
      (.eventTime // .lastTimestamp // .series.lastObservedTime // .metadata.creationTimestamp // "")
      | if type == "string" and length > 0
        then (sub("\\.[0-9]+Z$"; "Z") | try fromdateiso8601 catch null)
        else null
        end;
    all(.items[]?;
      . as $event |
      ($event.involvedObject.name // "") as $name |
      ($event | event_epoch) as $timestamp |
      if ($name | test("^(etcd-|kube-controller-manager-|kube-scheduler-)")) and
         ($timestamp != null) and (($now - $timestamp) <= $window)
      then (((($event.reason // "") + " " + ($event.message // ""))
             | test("lease|probe|unhealthy|failed|timeout"; "i")) | not)
      else true
      end)
  ' <<<"$events" >/dev/null || {
    printf 'recent kind control-plane lease, probe, or failure event detected for %s\n' \
      "$context" >&2
    return 1
  }
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

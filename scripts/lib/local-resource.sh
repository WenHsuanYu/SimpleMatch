#!/usr/bin/env bash

# Local resource measurement/analysis primitives.
# Collection depends explicitly on local-kind.sh and local-registry.sh. Analysis
# and rendering operate only on snapshot JSON so they remain deterministic and
# fixture-testable.

simplematch_local_resource_require_collection_dependencies() {
  declare -F simplematch_kind_exists >/dev/null || {
    printf '%s\n' 'local-resource.sh requires local-kind.sh before collection' >&2
    return 1
  }
  declare -F simplematch_kind_nodes >/dev/null || {
    printf '%s\n' 'local-resource.sh requires local-kind.sh before collection' >&2
    return 1
  }
  [[ -n "${SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME:-}" ]] || {
    printf '%s\n' 'local-resource.sh requires local-registry.sh before collection' >&2
    return 1
  }
}

simplematch_local_resource_host_path_bytes() {
  local path="$1"
  local size=""

  [[ -e "$path" ]] || { printf '%s\n' null; return 0; }
  size="$(du -s -B1 "$path" 2>/dev/null | awk 'NR == 1 {print $1}' || true)"
  if [[ ! "$size" =~ ^[0-9]+$ ]] && command -v sudo >/dev/null 2>&1; then
    size="$(sudo -n du -s -B1 "$path" 2>/dev/null | awk 'NR == 1 {print $1}' || true)"
  fi
  if [[ "$size" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "$size"
  else
    # Registry size is supplemental host attribution. Some Docker engines do
    # not expose a host-accessible mountpoint, so unknown is not a snapshot error.
    printf '%s\n' null
  fi
}

simplematch_local_resource_node_path_bytes() {
  local node="$1"
  local path="$2"
  local size

  size="$(docker exec "$node" du -s -B1 "$path" 2>/dev/null | awk 'NR == 1 {print $1}')" || {
    printf 'failed to measure %s on %s\n' "$path" "$node" >&2
    return 1
  }
  [[ "$size" =~ ^[0-9]+$ ]] || {
    printf 'invalid byte count for %s on %s: %s\n' "$path" "$node" "$size" >&2
    return 1
  }
  printf '%s\n' "$size"
}

simplematch_local_resource_docker_df_json() {
  local rows

  rows="$(docker system df --format '{{json .}}')" || return 1
  if [[ -z "$rows" ]]; then
    printf '%s\n' '[]'
    return 0
  fi
  jq -s . <<<"$rows"
}

simplematch_local_resource_cluster_fingerprint() {
  local cluster_name="$1"
  local node
  local nodes
  local identity=""
  local container_id

  nodes="$(simplematch_kind_nodes "$cluster_name")" || return 1
  [[ -n "$nodes" ]] || {
    printf 'kind cluster has no nodes for fingerprint: %s\n' "$cluster_name" >&2
    return 1
  }

  while IFS= read -r node; do
    [[ -n "$node" ]] || continue
    container_id="$(docker inspect --format '{{.Id}}' "$node")" || return 1
    [[ -n "$container_id" ]] || return 1
    identity+="${node}=${container_id}"$'\n'
  done <<<"$nodes"
  printf '%s' "$identity" | sort | sha256sum | awk '{print $1}'
}

simplematch_local_resource_non_baseline_pods_json() {
  local context="$1"
  local pods_json

  pods_json="$(kubectl --context "$context" get pods --all-namespaces -o json)" || return 1
  jq '[.items[]
    | select(.metadata.namespace != "kube-system")
    | select(.metadata.namespace != "local-path-storage")
    | {namespace:.metadata.namespace,name:.metadata.name,phase:(.status.phase // "Unknown")}]' <<<"$pods_json"
}

simplematch_local_resource_snapshot() {
  local cluster_name="$1"
  local context="kind-${cluster_name}"
  local generated_at
  local docker_df
  local registry_present=false
  local registry_mount=""
  local registry_bytes=null
  local kind_present=false
  local fingerprint=""
  local nodes_json='[]'
  local node
  local nodes
  local container_id
  local containerd_bytes
  local content_bytes
  local overlayfs_bytes
  local exited_count
  local notready_count
  local disposable_namespaces='[]'
  local non_baseline_pods='[]'
  local pv_count=0
  local namespaces_json
  local pv_json

  simplematch_local_resource_require_collection_dependencies || return 1

  generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  docker_df="$(simplematch_local_resource_docker_df_json)" || return 1

  if docker volume inspect "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME" >/dev/null 2>&1; then
    registry_present=true
    registry_mount="$(docker volume inspect "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME" --format '{{.Mountpoint}}')" || return 1
    registry_bytes="$(simplematch_local_resource_host_path_bytes "$registry_mount")"
  fi

  if simplematch_kind_exists "$cluster_name"; then
    kind_present=true
    fingerprint="$(simplematch_local_resource_cluster_fingerprint "$cluster_name")" || return 1
    nodes="$(simplematch_kind_nodes "$cluster_name")" || return 1
    [[ -n "$nodes" ]] || return 1

    while IFS= read -r node; do
      [[ -n "$node" ]] || continue
      container_id="$(docker inspect --format '{{.Id}}' "$node")" || return 1
      containerd_bytes="$(simplematch_local_resource_node_path_bytes "$node" /var/lib/containerd)" || return 1
      content_bytes="$(simplematch_local_resource_node_path_bytes "$node" /var/lib/containerd/io.containerd.content.v1.content)" || return 1
      overlayfs_bytes="$(simplematch_local_resource_node_path_bytes "$node" /var/lib/containerd/io.containerd.snapshotter.v1.overlayfs)" || return 1
      exited_count="$(docker exec "$node" crictl ps -a --state Exited -q | sed '/^$/d' | wc -l)" || return 1
      notready_count="$(docker exec "$node" crictl pods --state NotReady -q | sed '/^$/d' | wc -l)" || return 1
      [[ "$exited_count" =~ ^[0-9]+$ && "$notready_count" =~ ^[0-9]+$ ]] || return 1

      nodes_json="$(jq -nc \
        --argjson current "$nodes_json" \
        --arg name "$node" \
        --arg container_id "$container_id" \
        --argjson containerd "$containerd_bytes" \
        --argjson content "$content_bytes" \
        --argjson overlayfs "$overlayfs_bytes" \
        --argjson exited "$exited_count" \
        --argjson notready "$notready_count" \
        '$current + [{name:$name,container_id:$container_id,containerd_bytes:$containerd,content_bytes:$content,overlayfs_bytes:$overlayfs,exited_containers:$exited,notready_sandboxes:$notready}]')" || return 1
    done <<<"$nodes"

    namespaces_json="$(kubectl --context "$context" get namespaces -l simplematch.io/lifecycle=disposable -o json)" || return 1
    disposable_namespaces="$(jq '[.items[].metadata.name]' <<<"$namespaces_json")" || return 1
    non_baseline_pods="$(simplematch_local_resource_non_baseline_pods_json "$context")" || return 1
    pv_json="$(kubectl --context "$context" get pv -o json)" || return 1
    pv_count="$(jq '.items | length' <<<"$pv_json")" || return 1
  fi

  jq -n \
    --argjson schema_version 1 \
    --arg generated_at_utc "$generated_at" \
    --arg cluster "$cluster_name" \
    --argjson docker_system_df "$docker_df" \
    --arg registry_volume "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME" \
    --argjson registry_present "$registry_present" \
    --arg registry_mountpoint "$registry_mount" \
    --argjson registry_bytes "$registry_bytes" \
    --argjson kind_present "$kind_present" \
    --arg cluster_fingerprint "$fingerprint" \
    --argjson nodes "$nodes_json" \
    --argjson disposable_namespaces "$disposable_namespaces" \
    --argjson non_baseline_pods "$non_baseline_pods" \
    --argjson pv_count "$pv_count" \
    '{
      schema_version:$schema_version,
      generated_at_utc:$generated_at_utc,
      cluster:$cluster,
      docker:{system_df:$docker_system_df},
      registry:{volume:$registry_volume,present:$registry_present,mountpoint:$registry_mountpoint,size_bytes:$registry_bytes},
      kind:{
        present:$kind_present,
        cluster_fingerprint:$cluster_fingerprint,
        nodes:$nodes,
        totals:{
          containerd_bytes:([$nodes[].containerd_bytes] | add // 0),
          content_bytes:([$nodes[].content_bytes] | add // 0),
          overlayfs_bytes:([$nodes[].overlayfs_bytes] | add // 0),
          exited_containers:([$nodes[].exited_containers] | add // 0),
          notready_sandboxes:([$nodes[].notready_sandboxes] | add // 0)
        }
      },
      kubernetes:{
        disposable_namespaces:$disposable_namespaces,
        non_baseline_pods:$non_baseline_pods,
        pv_count:$pv_count
      }
    }'
}

simplematch_local_resource_validate_snapshot_file() {
  local snapshot_file="$1"

  jq -e '
    def nonnegative_integer: type == "number" and . >= 0 and floor == .;
    def node_shape:
      (.name | type == "string" and length > 0)
      and (.container_id | type == "string" and length > 0)
      and (.containerd_bytes | nonnegative_integer)
      and (.content_bytes | nonnegative_integer)
      and (.overlayfs_bytes | nonnegative_integer)
      and (.exited_containers | nonnegative_integer)
      and (.notready_sandboxes | nonnegative_integer);
    .schema_version == 1
    and (.cluster | type == "string" and length > 0)
    and (.kind.present | type == "boolean")
    and (.kind.nodes | type == "array")
    and (all(.kind.nodes[]; node_shape))
    and ((.kind.nodes | map(.name) | length) == (.kind.nodes | map(.name) | unique | length))
    and ((.kind.nodes | map(.container_id) | length) == (.kind.nodes | map(.container_id) | unique | length))
    and (.kind.totals.containerd_bytes | nonnegative_integer)
    and (.kind.totals.content_bytes | nonnegative_integer)
    and (.kind.totals.overlayfs_bytes | nonnegative_integer)
    and (.kind.totals.exited_containers | nonnegative_integer)
    and (.kind.totals.notready_sandboxes | nonnegative_integer)
    and (.kind.totals.containerd_bytes == ([.kind.nodes[].containerd_bytes] | add // 0))
    and (.kind.totals.content_bytes == ([.kind.nodes[].content_bytes] | add // 0))
    and (.kind.totals.overlayfs_bytes == ([.kind.nodes[].overlayfs_bytes] | add // 0))
    and (.kind.totals.exited_containers == ([.kind.nodes[].exited_containers] | add // 0))
    and (.kind.totals.notready_sandboxes == ([.kind.nodes[].notready_sandboxes] | add // 0))
    and (.kubernetes.disposable_namespaces | type == "array")
    and (.kubernetes.non_baseline_pods | type == "array")
    and (.kubernetes.pv_count | nonnegative_integer)
  ' "$snapshot_file" >/dev/null
}

simplematch_local_resource_assert_clean_baseline_json() {
  local snapshot_file="$1"

  simplematch_local_resource_validate_snapshot_file "$snapshot_file" || return 1
  jq -e '
    .kind.present == true
    and (.kind.nodes | length) > 0
    and (.kubernetes.disposable_namespaces | length) == 0
    and (.kubernetes.non_baseline_pods | length) == 0
    and .kubernetes.pv_count == 0
  ' "$snapshot_file" >/dev/null
}

simplematch_local_resource_wait_clean_cluster() {
  local cluster_name="$1"
  local timeout_seconds="${2:-120}"
  local context="kind-${cluster_name}"
  local deadline=$((SECONDS + timeout_seconds))
  local disposable_count
  local non_baseline_count
  local pv_count
  local namespaces_json
  local pv_json
  local non_baseline_json

  while ((SECONDS < deadline)); do
    if ! namespaces_json="$(kubectl --context "$context" get namespaces -l simplematch.io/lifecycle=disposable -o json)"; then
      sleep 1
      continue
    fi
    if ! non_baseline_json="$(simplematch_local_resource_non_baseline_pods_json "$context")"; then
      sleep 1
      continue
    fi
    if ! pv_json="$(kubectl --context "$context" get pv -o json)"; then
      sleep 1
      continue
    fi

    disposable_count="$(jq '.items | length' <<<"$namespaces_json")" || return 1
    non_baseline_count="$(jq 'length' <<<"$non_baseline_json")" || return 1
    pv_count="$(jq '.items | length' <<<"$pv_json")" || return 1
    if [[ "$disposable_count" -eq 0 && "$non_baseline_count" -eq 0 && "$pv_count" -eq 0 ]]; then
      return 0
    fi
    sleep 1
  done

  simplematch_warn "cluster did not reach clean baseline state within ${timeout_seconds}s: $cluster_name"
  return 1
}

simplematch_local_resource_compare_files() {
  local baseline_file="$1"
  local current_file="$2"
  local baseline_cluster
  local current_cluster
  local baseline_fingerprint
  local current_fingerprint

  simplematch_local_resource_validate_snapshot_file "$baseline_file" || {
    simplematch_warn 'resource baseline snapshot is malformed or unsupported'
    return 2
  }
  simplematch_local_resource_validate_snapshot_file "$current_file" || {
    simplematch_warn 'current resource snapshot is malformed or unsupported'
    return 2
  }

  baseline_cluster="$(jq -r '.cluster' "$baseline_file")"
  current_cluster="$(jq -r '.cluster' "$current_file")"
  [[ "$baseline_cluster" == "$current_cluster" ]] || {
    simplematch_warn 'resource baseline cluster does not match current cluster'
    return 2
  }

  baseline_fingerprint="$(jq -r '.kind.cluster_fingerprint // empty' "$baseline_file")"
  current_fingerprint="$(jq -r '.kind.cluster_fingerprint // empty' "$current_file")"
  [[ -n "$baseline_fingerprint" && "$baseline_fingerprint" == "$current_fingerprint" ]] || {
    simplematch_warn 'resource baseline belongs to a different kind cluster generation; establish a new baseline'
    return 2
  }

  if ! jq -e --slurpfile baseline "$baseline_file" --slurpfile current "$current_file" '
      (($baseline[0].kind.nodes | map(.name) | sort) == ($current[0].kind.nodes | map(.name) | sort))
    ' <<<"null" >/dev/null; then
    simplematch_warn 'resource baseline node set does not match current cluster nodes'
    return 2
  fi

  jq -n --slurpfile baseline "$baseline_file" --slurpfile current "$current_file" '
    def delta($a;$b): ($b - $a);
    ($baseline[0]) as $b |
    ($current[0]) as $c |
    ($c.kind.totals.containerd_bytes - $b.kind.totals.containerd_bytes) as $containerd_delta |
    (($c.kubernetes.disposable_namespaces | length) == 0
      and ($c.kubernetes.non_baseline_pods | length) == 0
      and $c.kubernetes.pv_count == 0) as $idle |
    {
      schema_version:1,
      baseline_generated_at_utc:$b.generated_at_utc,
      current_generated_at_utc:$c.generated_at_utc,
      cluster:$c.cluster,
      cluster_fingerprint:$c.kind.cluster_fingerprint,
      cluster_idle:$idle,
      deltas:{
        containerd_bytes:$containerd_delta,
        content_bytes:delta($b.kind.totals.content_bytes;$c.kind.totals.content_bytes),
        overlayfs_bytes:delta($b.kind.totals.overlayfs_bytes;$c.kind.totals.overlayfs_bytes),
        exited_containers:delta($b.kind.totals.exited_containers;$c.kind.totals.exited_containers),
        notready_sandboxes:delta($b.kind.totals.notready_sandboxes;$c.kind.totals.notready_sandboxes),
        registry_bytes:(if ($b.registry.size_bytes|type)=="number" and ($c.registry.size_bytes|type)=="number" then $c.registry.size_bytes-$b.registry.size_bytes else null end)
      },
      nodes:[
        $c.kind.nodes[] as $node |
        ($b.kind.nodes[] | select(.name == $node.name)) as $base |
        {
          name:$node.name,
          containerd_bytes:($node.containerd_bytes-$base.containerd_bytes),
          content_bytes:($node.content_bytes-$base.content_bytes),
          overlayfs_bytes:($node.overlayfs_bytes-$base.overlayfs_bytes),
          exited_containers:($node.exited_containers-$base.exited_containers),
          notready_sandboxes:($node.notready_sandboxes-$base.notready_sandboxes)
        }
      ],
      assessment:(
        if $containerd_delta <= 0 then "NO_CONTAINERD_GROWTH"
        elif $idle then "IDLE_RESIDUAL_GROWTH"
        else "ACTIVE_WORKLOAD_GROWTH"
        end
      ),
      recycle_candidate:($containerd_delta > 0 and $idle)
    }'
}

simplematch_local_resource_render_snapshot_file() {
  local snapshot_file="$1"

  jq -er '
    if .schema_version != 1 or (.cluster|type)!="string" or (.kind.totals.containerd_bytes|type)!="number" then
      error("malformed resource snapshot")
    else
      (.kind.cluster_fingerprint // "absent") as $fingerprint |
      (.registry.size_bytes // "unknown") as $registry_bytes |
      "cluster=\(.cluster)",
      "cluster_fingerprint=\($fingerprint)",
      "registry_bytes=\($registry_bytes)",
      "containerd_bytes=\(.kind.totals.containerd_bytes)",
      "content_bytes=\(.kind.totals.content_bytes)",
      "overlayfs_bytes=\(.kind.totals.overlayfs_bytes)",
      "exited_containers=\(.kind.totals.exited_containers)",
      "notready_sandboxes=\(.kind.totals.notready_sandboxes)",
      "disposable_namespaces=\(.kubernetes.disposable_namespaces|length)",
      "non_baseline_pods=\(.kubernetes.non_baseline_pods|length)",
      "pv_count=\(.kubernetes.pv_count)",
      (.kind.nodes[]? | "node=\(.name) containerd_bytes=\(.containerd_bytes) content_bytes=\(.content_bytes) overlayfs_bytes=\(.overlayfs_bytes) exited=\(.exited_containers) notready=\(.notready_sandboxes)")
    end
  ' "$snapshot_file"
}

simplematch_local_resource_render_comparison_file() {
  local comparison_file="$1"

  jq -er '
    if .schema_version != 1 or (.assessment|type)!="string" or (.deltas.containerd_bytes|type)!="number" then
      error("malformed resource comparison")
    else
      (.deltas.registry_bytes // "unknown") as $registry_delta |
      "assessment=\(.assessment)",
      "cluster_idle=\(.cluster_idle)",
      "recycle_candidate=\(.recycle_candidate)",
      "containerd_delta_bytes=\(.deltas.containerd_bytes)",
      "content_delta_bytes=\(.deltas.content_bytes)",
      "overlayfs_delta_bytes=\(.deltas.overlayfs_bytes)",
      "registry_delta_bytes=\($registry_delta)",
      "exited_delta=\(.deltas.exited_containers)",
      "notready_delta=\(.deltas.notready_sandboxes)",
      (.nodes[]? | "node_delta=\(.name) containerd=\(.containerd_bytes) content=\(.content_bytes) overlayfs=\(.overlayfs_bytes) exited=\(.exited_containers) notready=\(.notready_sandboxes)")
    end
  ' "$comparison_file"
}

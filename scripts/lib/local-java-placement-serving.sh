#!/usr/bin/env bash

set -euo pipefail

# The Java placement observer deliberately has one representative application
# target.  It proves the shared deployment contract and health boundaries
# without becoming a second full-local certification orchestrator.
JAVA_PLACEMENT_SERVING_PROFILE=java-placement-serving
JAVA_PLACEMENT_SERVING_SERVICE=query-service
JAVA_PLACEMENT_SERVING_CONTAINER=query-service
JAVA_PLACEMENT_SERVING_PORT=8086
JAVA_PLACEMENT_SERVING_REPLICAS=2
JAVA_PLACEMENT_SERVING_NODE_POOL=local-resilience
JAVA_PLACEMENT_SERVING_READINESS_PATH=/actuator/health/readiness
JAVA_PLACEMENT_SERVING_LIVENESS_PATH=/actuator/health/liveness
JAVA_PLACEMENT_SERVING_STARTUP_PATH=/actuator/health/readiness
JAVA_PLACEMENT_SERVING_APPLICATION_PATH=/api/v1/freshness

java_placement_serving_probe_contract_is_valid() {
  local deployment_file="$1"

  [[ -s "$deployment_file" ]] || return 1
  jq -e \
    --arg container "$JAVA_PLACEMENT_SERVING_CONTAINER" \
    --arg startup_path "$JAVA_PLACEMENT_SERVING_STARTUP_PATH" \
    --arg readiness_path "$JAVA_PLACEMENT_SERVING_READINESS_PATH" \
    --arg liveness_path "$JAVA_PLACEMENT_SERVING_LIVENESS_PATH" \
    --arg port http \
    '
      ([.spec.template.spec.containers[]?
        | select(.name == $container)] | length) == 1
      and ([.spec.template.spec.containers[]?
        | select(.name == $container)][0]) as $containerSpec
      | ($containerSpec.startupProbe // {}) as $startup
      | ($containerSpec.readinessProbe // {}) as $readiness
      | ($containerSpec.livenessProbe // {}) as $liveness
      | ($startup.httpGet.path == $startup_path
         and $startup.httpGet.port == $port)
      and ($readiness.httpGet.path == $readiness_path
           and $readiness.httpGet.port == $port)
      and ($liveness.httpGet.path == $liveness_path
           and $liveness.httpGet.port == $port)
    ' "$deployment_file" >/dev/null
}

# Normalize Kubernetes API responses once into the stable evidence shape used
# by the runner and its contract validator.
java_placement_serving_runtime_snapshot() {
  local deployment_file="$1"
  local pods_file="$2"
  local nodes_file="$3"
  local endpoint_slices_file="$4"

  [[ -s "$deployment_file" && -s "$pods_file" && -s "$nodes_file" &&
    -s "$endpoint_slices_file" ]] || return 1
  jq -n \
    --slurpfile deployment "$deployment_file" \
    --slurpfile pods "$pods_file" \
    --slurpfile nodes "$nodes_file" \
    --slurpfile endpoint_slices "$endpoint_slices_file" \
    --arg service "$JAVA_PLACEMENT_SERVING_SERVICE" \
    --arg container "$JAVA_PLACEMENT_SERVING_CONTAINER" \
    --arg node_pool "$JAVA_PLACEMENT_SERVING_NODE_POOL" \
    --argjson port "$JAVA_PLACEMENT_SERVING_PORT" '
      ($deployment[0]) as $deploymentObject
      | ($pods[0].items // []) as $podItems
      | ($nodes[0].items // []) as $nodeItems
      | ($endpoint_slices[0].items // []) as $endpointItems
      | ([ $podItems[]?
           | select(.metadata.labels["app.kubernetes.io/name"] == $service
                    and .metadata.labels["app.kubernetes.io/component"] == "java-service")
           | ([.status.containerStatuses[]?
                | select(.name == $container)] | if length == 1 then .[0] else {} end) as $containerStatus
           | {
               name: (.metadata.name // ""),
               uid: (.metadata.uid // ""),
               node: (.spec.nodeName // ""),
               pod_ip: (.status.podIP // ""),
               phase: (.status.phase // ""),
               ready: any(.status.conditions[]?;
                 .type == "Ready" and .status == "True"),
               restart_count: ($containerStatus.restartCount // -1),
               started_at: ($containerStatus.state.running.startedAt // ""),
               image: ($containerStatus.image // ""),
               image_id: ($containerStatus.imageID // "")
             }
         ] | sort_by(.name)) as $podRows
      | ([ $endpointItems[]?.endpoints[]?
           | select(.conditions.ready == true)
           | .addresses[]? ] | unique) as $readyEndpointAddresses
      | ([ $nodeItems[]?
           | select(.metadata.labels["simplematch.io/node-pool"] == $node_pool)
           | .metadata.name ] | unique) as $eligibleNodes
      | ([ $podRows[].node ] | unique) as $podNodes
      | ([ $podRows[].pod_ip ] | map(select(length > 0)) | unique) as $podIps
      | ($deploymentObject.spec.template.spec.nodeSelector // {}) as $selector
      | ($deploymentObject.status // {}) as $deploymentStatus
      | ($deploymentObject.spec // {}) as $deploymentSpec
      | {
          deployment_name: ($deploymentObject.metadata.name // ""),
          deployment_uid: ($deploymentObject.metadata.uid // ""),
          deployment_generation: ($deploymentObject.metadata.generation // 0),
          desired_replicas: ($deploymentSpec.replicas // 0),
          ready_replicas: ($deploymentStatus.readyReplicas // 0),
          available_replicas: ($deploymentStatus.availableReplicas // 0),
          updated_replicas: ($deploymentStatus.updatedReplicas // 0),
          node_selector: $selector,
          node_pool: $node_pool,
          eligible_node_names: $eligibleNodes,
          pods: $podRows,
          pod_count: ($podRows | length),
          ready_pod_count: ($podRows | map(select(.ready == true)) | length),
          pod_nodes: $podNodes,
          distinct_nodes: ($podNodes | length),
          pod_ips: $podIps,
          ready_endpoint_addresses: $readyEndpointAddresses,
          ready_endpoint_count: ($readyEndpointAddresses | length),
          startup_completed_pod_count: (
            $podRows
            | map(select((.started_at | type) == "string" and (.started_at | length) > 0))
            | length
          ),
          container_port_match_count: (
            $deploymentSpec.template.spec.containers
            | map(select(.name == $container) | .ports[]?
                | select(.name == "http" and .containerPort == $port))
            | length
          )
      }
    '
}

# Validate only the normalized shape; Kubernetes API traversal stays above.
java_placement_serving_snapshot_json_is_ready() {
  local snapshot_json="$1"

  jq -e \
    --arg service "$JAVA_PLACEMENT_SERVING_SERVICE" \
    --arg node_pool "$JAVA_PLACEMENT_SERVING_NODE_POOL" \
    --argjson replicas "$JAVA_PLACEMENT_SERVING_REPLICAS" '
      (.pod_count == $replicas)
      and (.deployment_name == $service)
      and (.desired_replicas == $replicas)
      and (.container_port_match_count == 1)
      and (.ready_replicas == $replicas)
      and (.available_replicas == $replicas)
      and (.updated_replicas == $replicas)
      and (.pods | all(.[]; .phase == "Running"))
      and (.pods | all(.[]; .ready == true))
      and (.pods | all(.[];
        ((.name | type) == "string" and (.name | length) > 0)))
      and (.pods | all(.[];
        ((.uid | type) == "string" and (.uid | length) > 0)))
      and (.pods | all(.[];
        ((.node | type) == "string" and (.node | length) > 0)))
      and (.pods | all(.[];
        ((.pod_ip | type) == "string" and (.pod_ip | length) > 0)))
      and (.pods | all(.[];
        ((.restart_count | type) == "number" and .restart_count >= 0)))
      and (.pods | all(.[];
        ((.started_at | type) == "string" and (.started_at | length) > 0)))
      and (.pods | all(.[];
        ((.image | type) == "string" and (.image | length) > 0)))
      and (.pods | all(.[];
        ((.image_id | type) == "string"
         and (.image_id | test("sha256:[0-9a-f]{64}$")))))
      and ((.pods | map(.uid) | unique | length) == $replicas)
      and (.distinct_nodes >= 2)
      and (. as $snapshot
        | $snapshot.pods
        | all(.[];
          .node as $node | ($snapshot.eligible_node_names | index($node)) != null))
      and (.ready_endpoint_count >= $replicas)
      and (. as $snapshot
        | $snapshot.ready_endpoint_addresses
        | all(.[];
          . as $endpoint | ($snapshot.pod_ips | index($endpoint)) != null))
      and (.node_selector == {"simplematch.io/node-pool": $node_pool})
    ' <<<"$snapshot_json" >/dev/null
}

java_placement_serving_runtime_snapshot_is_ready() {
  local snapshot_json

  snapshot_json="$(java_placement_serving_runtime_snapshot "$@")" || return 1
  java_placement_serving_snapshot_json_is_ready "$snapshot_json"
}

java_placement_serving_snapshot_file_is_ready() {
  local snapshot_file="$1"

  [[ -s "$snapshot_file" ]] || return 1
  java_placement_serving_snapshot_json_is_ready "$(<"$snapshot_file")"
}

java_placement_serving_sha256_digest() {
  local file="$1"
  local digest

  [[ -f "$file" && ! -L "$file" ]] || return 1
  digest="$(sha256sum "$file")" || return 1
  digest="${digest%% *}"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf 'sha256:%s\n' "$digest"
}

java_placement_serving_redis_snapshot_is_expected() {
  local snapshot_file="$1"
  local expected_replicas="$2"

  [[ -s "$snapshot_file" ]] || return 1
  [[ "$expected_replicas" =~ ^[0-9]+$ ]] || return 1
  jq -e --argjson expected "$expected_replicas" '
    (.deployment_name == "redis")
    and (.desired_replicas == $expected)
    and (.ready_replicas == $expected)
    and (.ready_pod_count == $expected)
    and if $expected == 0
        then (.pods | all(.[]; .ready != true))
        else (.pods | any(.[]; .ready == true))
        end
  ' "$snapshot_file" >/dev/null
}

java_placement_serving_report_body_files_are_valid() {
  local report_file="$1"
  local report_dir body_file expected_digest expected_type expected_status
  local actual_files expected_file_list
  local body_path actual_digest actual_type
  local -a expected_files=(
    health/baseline-liveness.json
    health/baseline-readiness.json
    health/baseline-serving.json
    health/redis-outage-liveness.json
    health/redis-outage-readiness.json
    health/redis-outage-serving.json
    health/restored-liveness.json
    health/restored-readiness.json
    health/restored-serving.json
  )

  report_dir="${report_file%/*}"
  [[ "$report_dir" != "$report_file" ]] || report_dir=.
  report_dir="$(cd -- "$report_dir" && pwd)" || return 1
  [[ -d "$report_dir/health" && ! -L "$report_dir/health" ]] || return 1
  expected_file_list="$(printf '%s\n' "${expected_files[@]}")"
  expected_file_list="${expected_file_list%$'\n'}"
  actual_files="$(jq -r '
    [
      .observations.baseline.readiness,
      .observations.baseline.liveness,
      .observations.baseline.serving,
      .observations.redis_outage.readiness,
      .observations.redis_outage.liveness,
      .observations.redis_outage.serving,
      .observations.restored.readiness,
      .observations.restored.liveness,
      .observations.restored.serving
    ]
    | map(.body_file)
    | sort
    | .[]
  ' "$report_file")" || return 1
  [[ "$actual_files" == "$expected_file_list" ]] || return 1
  while IFS=$'\t' read -r body_file expected_digest expected_type expected_status; do
    body_path="$report_dir/$body_file"
    [[ -f "$body_path" && ! -L "$body_path" ]] || return 1
    actual_digest="$(java_placement_serving_sha256_digest "$body_path")" || return 1
    [[ "$actual_digest" == "$expected_digest" ]] || return 1
    actual_type="$(jq -r 'type' "$body_path")" || return 1
    [[ "$actual_type" == "$expected_type" ]] || return 1
    if [[ "$expected_status" == UP ]]; then
      jq -e '.status == "UP"' "$body_path" >/dev/null || return 1
    elif [[ "$expected_status" == SERVING ]]; then
      [[ "$actual_type" == object ]] || return 1
    else
      return 1
    fi
  done < <(
    jq -r '
      [
        .observations.baseline.readiness,
        .observations.baseline.liveness,
        .observations.baseline.serving,
        .observations.redis_outage.readiness,
        .observations.redis_outage.liveness,
        .observations.redis_outage.serving,
        .observations.restored.readiness,
        .observations.restored.liveness,
        .observations.restored.serving
      ][]
      | [.body_file, .body_sha256, .body_type, .body_status]
      | @tsv
    ' "$report_file"
  )
}

java_placement_serving_report_redis_snapshot_files_are_valid() {
  local report_file="$1"
  local report_dir snapshot_file expected_digest expected_replicas
  local actual_files expected_file_list snapshot_path actual_digest
  local -a expected_files=(
    placement/redis-after.json
    placement/redis-before.json
    placement/redis-during.json
  )

  report_dir="${report_file%/*}"
  [[ "$report_dir" != "$report_file" ]] || report_dir=.
  report_dir="$(cd -- "$report_dir" && pwd)" || return 1
  expected_file_list="$(printf '%s\n' "${expected_files[@]}")"
  expected_file_list="${expected_file_list%$'\n'}"
  actual_files="$(jq -r '
    [
      .redis_outage.snapshots.before,
      .redis_outage.snapshots.during,
      .redis_outage.snapshots.after
    ]
    | map(.file)
    | sort
    | .[]
  ' "$report_file")" || return 1
  [[ "$actual_files" == "$expected_file_list" ]] || return 1
  while IFS=$'\t' read -r snapshot_file expected_digest expected_replicas; do
    snapshot_path="$report_dir/$snapshot_file"
    [[ -f "$snapshot_path" && ! -L "$snapshot_path" ]] || return 1
    actual_digest="$(java_placement_serving_sha256_digest "$snapshot_path")" || return 1
    [[ "$actual_digest" == "$expected_digest" ]] || return 1
    java_placement_serving_redis_snapshot_is_expected \
      "$snapshot_path" "$expected_replicas" || return 1
  done < <(
    jq -r '
      [
        [.redis_outage.snapshots.before.file,
         .redis_outage.snapshots.before.sha256,
         .redis_outage.snapshots.before.replicas],
        [.redis_outage.snapshots.during.file,
         .redis_outage.snapshots.during.sha256,
         .redis_outage.snapshots.during.replicas],
        [.redis_outage.snapshots.after.file,
         .redis_outage.snapshots.after.sha256,
         .redis_outage.snapshots.after.replicas]
      ][]
      | @tsv
    ' "$report_file"
  )
}

java_placement_serving_report_placement_files_are_valid() {
  local report_file="$1"
  local report_dir snapshot_file expected_digest
  local actual_files expected_file_list snapshot_path actual_digest
  local -a expected_files=(
    placement/baseline.json
    placement/redis-outage.json
    placement/restored.json
  )

  report_dir="${report_file%/*}"
  [[ "$report_dir" != "$report_file" ]] || report_dir=.
  report_dir="$(cd -- "$report_dir" && pwd)" || return 1
  expected_file_list="$(printf '%s\n' "${expected_files[@]}")"
  expected_file_list="${expected_file_list%$'\n'}"
  actual_files="$(jq -r '
    [
      .placement.snapshots.baseline,
      .placement.snapshots.outage,
      .placement.snapshots.restored
    ]
    | map(.file)
    | sort
    | .[]
  ' "$report_file")" || return 1
  [[ "$actual_files" == "$expected_file_list" ]] || return 1
  while IFS=$'\t' read -r snapshot_file expected_digest; do
    snapshot_path="$report_dir/$snapshot_file"
    [[ -f "$snapshot_path" && ! -L "$snapshot_path" ]] || return 1
    actual_digest="$(java_placement_serving_sha256_digest "$snapshot_path")" || return 1
    [[ "$actual_digest" == "$expected_digest" ]] || return 1
    java_placement_serving_snapshot_file_is_ready "$snapshot_path" || return 1
  done < <(
    jq -r '
      [
        [.placement.snapshots.baseline.file,
         .placement.snapshots.baseline.sha256],
        [.placement.snapshots.outage.file,
         .placement.snapshots.outage.sha256],
        [.placement.snapshots.restored.file,
         .placement.snapshots.restored.sha256]
      ][]
      | @tsv
    ' "$report_file"
  )
}

java_placement_serving_report_is_passed() {
  local report_file="$1"

  [[ -s "$report_file" ]] || return 1
  jq -e \
    --arg profile "$JAVA_PLACEMENT_SERVING_PROFILE" \
    --arg service "$JAVA_PLACEMENT_SERVING_SERVICE" \
    --arg container "$JAVA_PLACEMENT_SERVING_CONTAINER" \
    --arg node_pool "$JAVA_PLACEMENT_SERVING_NODE_POOL" \
    --arg startup_path "$JAVA_PLACEMENT_SERVING_STARTUP_PATH" \
    --arg readiness_path "$JAVA_PLACEMENT_SERVING_READINESS_PATH" \
    --arg liveness_path "$JAVA_PLACEMENT_SERVING_LIVENESS_PATH" \
    --arg application_path "$JAVA_PLACEMENT_SERVING_APPLICATION_PATH" \
    --argjson port "$JAVA_PLACEMENT_SERVING_PORT" \
    --argjson replicas "$JAVA_PLACEMENT_SERVING_REPLICAS" '
      def healthy_probe($probe; $expected_path):
        ($probe.http_status == 200)
        and ($probe.path == $expected_path)
        and ($probe.body_status == "UP")
        and ($probe.body_file | type == "string" and length > 0)
        and ($probe.body_sha256 | test("^sha256:[0-9a-f]{64}$"));
      def healthy_serving($probe):
        ($probe.http_status == 200)
        and ($probe.path == $application_path)
        and ($probe.body_type == "object")
        and ($probe.body_file | type == "string" and length > 0)
        and ($probe.body_sha256 | test("^sha256:[0-9a-f]{64}$"));
      def healthy_observation($observation):
        healthy_probe($observation.readiness; $readiness_path)
        and healthy_probe($observation.liveness; $liveness_path)
        and healthy_serving($observation.serving);
      (.schema_version == 1)
      and (.profile == $profile)
      and (.status == "PASS")
      and (.cluster == "simplematch-live")
      and (.context == "kind-simplematch-live")
      and (.namespace | type == "string" and length > 0)
      and (.namespace_run_id | type == "string" and length > 0)
      and (.source_revision | test("^[0-9a-f]{40}$"))
      and (.target.service == $service)
      and (.target.container == $container)
      and (.target.port == $port)
      and (.target.pod | type == "string" and length > 0)
      and (.target.pod_uid | type == "string" and length > 0)
      and (.target.node | type == "string" and length > 0)
      and (.target.replicas == $replicas)
      and (.placement.node_pool == $node_pool)
      and (.placement.pod_count == $replicas)
      and (.placement.ready_pod_count == $replicas)
      and (.placement.distinct_nodes >= 2)
      and (.placement.ready_endpoint_count >= $replicas)
      and (.placement.startup_completed_pod_count == $replicas)
      and (.placement.image_ids | type == "array" and length > 0
           and all(.[]; type == "string" and test("sha256:[0-9a-f]{64}$")))
      and (.placement.pod_uid_unchanged == true)
      and (.placement.restart_count_unchanged == true)
      and (.probes == {
        startup:{path:$startup_path,port:"http"},
        readiness:{path:$readiness_path,port:"http"},
        liveness:{path:$liveness_path,port:"http"}
      })
      and healthy_observation(.observations.baseline)
      and healthy_observation(.observations.redis_outage)
      and healthy_observation(.observations.restored)
      and (.redis_outage.replicas_before == 1)
      and (.redis_outage.replicas_during == 0)
      and (.redis_outage.replicas_after == 1)
      and (.redis_outage.observed_seconds >= 1)
      and (.claim_boundary == [
        "source-aligned query-service placement and serving",
        "query-service readiness and liveness remained healthy during a Redis outage",
        "no query-service Pod replacement or restart was observed during the bounded outage",
        "diagnostic-only evidence; not a full-local aggregate certification"
      ])
    ' "$report_file" >/dev/null || return 1
  java_placement_serving_report_placement_files_are_valid "$report_file" || return 1
  java_placement_serving_report_redis_snapshot_files_are_valid "$report_file" || return 1
  java_placement_serving_report_body_files_are_valid "$report_file"
}

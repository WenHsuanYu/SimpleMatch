#!/usr/bin/env bash

set -euo pipefail

# The Java placement observer deliberately has one representative application
# target.  It proves the shared deployment contract and health boundaries
# without becoming a second full-local certification orchestrator.
JAVA_PLACEMENT_SERVING_PROFILE=java-placement-serving
JAVA_PLACEMENT_SERVING_CLUSTER=simplematch-live
JAVA_PLACEMENT_SERVING_CONTEXT='kind-simplematch-live'
JAVA_PLACEMENT_SERVING_SERVICE=query-service
JAVA_PLACEMENT_SERVING_CONTAINER=query-service
JAVA_PLACEMENT_SERVING_PORT=8086
JAVA_PLACEMENT_SERVING_REPLICAS=2
JAVA_PLACEMENT_SERVING_REDIS_DEPLOYMENT=redis
JAVA_PLACEMENT_SERVING_NODE_POOL=local-resilience
JAVA_PLACEMENT_SERVING_READINESS_PATH=/actuator/health/readiness
JAVA_PLACEMENT_SERVING_LIVENESS_PATH=/actuator/health/liveness
JAVA_PLACEMENT_SERVING_STARTUP_PATH=/actuator/health/readiness
JAVA_PLACEMENT_SERVING_APPLICATION_PATH=/api/v1/freshness
JAVA_PLACEMENT_SERVING_CLAIM_BOUNDARY_JSON='[
  "source-aligned query-service placement and serving",
  "query-service readiness and liveness remained healthy during a Redis outage",
  "no query-service Pod replacement or restart was observed during the bounded outage",
  "diagnostic-only evidence; not a full-local aggregate certification"
]'
JAVA_PLACEMENT_SERVING_EVIDENCE_FILES_JSON='[
  "placement/baseline.json",
  "placement/redis-outage.json",
  "placement/restored.json",
  "placement/redis-before.json",
  "placement/redis-during.json",
  "placement/redis-after.json",
  "health/baseline-readiness.json",
  "health/baseline-liveness.json",
  "health/baseline-serving.json",
  "health/redis-outage-readiness.json",
  "health/redis-outage-liveness.json",
  "health/redis-outage-serving.json",
  "health/restored-readiness.json",
  "health/restored-liveness.json",
  "health/restored-serving.json"
]'
# The outage may begin just after a Pod starts.  Cover the 20-second liveness
# initial delay, three 10-second failures, and one extra period for detection.
JAVA_PLACEMENT_SERVING_MIN_OBSERVE_SECONDS=60

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

  [[ -s "$deployment_file" && -s "$pods_file" && -s "$nodes_file" ]] || return 1
  jq -n \
    --slurpfile deployment "$deployment_file" \
    --slurpfile pods "$pods_file" \
    --slurpfile nodes "$nodes_file" \
    --arg service "$JAVA_PLACEMENT_SERVING_SERVICE" \
    --arg container "$JAVA_PLACEMENT_SERVING_CONTAINER" \
    --arg node_pool "$JAVA_PLACEMENT_SERVING_NODE_POOL" \
    '
      ($deployment[0]) as $deploymentObject
      | ($pods[0].items // []) as $podItems
      | ($nodes[0].items // []) as $nodeItems
      | ([ $podItems[]?
           | select(.metadata.labels["app.kubernetes.io/name"] == $service
                    and .metadata.labels["app.kubernetes.io/component"] == "java-service")
           | ([.status.containerStatuses[]?
               | select(.name == $container)]
              | if length == 1 then .[0] else {} end) as $containerStatus
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
         ] | sort_by(.name)) as $podRows
      | ([ $nodeItems[]?
           | select(.metadata.labels["simplematch.io/node-pool"] == $node_pool)
           | .metadata.name ] | unique) as $eligibleNodes
      | ([ $podRows[].node ] | unique) as $podNodes
      | ($deploymentObject.status // {}) as $deploymentStatus
      | ($deploymentObject.spec // {}) as $deploymentSpec
      | {
          deployment_name: ($deploymentObject.metadata.name // ""),
          desired_replicas: ($deploymentSpec.replicas // 0),
          ready_replicas: ($deploymentStatus.readyReplicas // 0),
          node_pool: $node_pool,
          eligible_node_names: $eligibleNodes,
          pods: $podRows,
          pod_count: ($podRows | length),
          ready_pod_count: ($podRows | map(select(.ready == true)) | length),
          distinct_nodes: ($podNodes | length)
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
      def text: type == "string" and length > 0;
      def immutable_image: text and test("sha256:[0-9a-f]{64}$");
      def valid_pod:
        (.name | text)
        and (.uid | text)
        and (.node | text)
        and (.phase == "Running")
        and (.ready == true)
        and (.restart_count | numbers and . >= 0)
        and (.image_id | immutable_image);
      (.deployment_name == $service)
      and (.node_pool == $node_pool)
      and (.desired_replicas == $replicas)
      and (.ready_replicas == $replicas)
      and (.pod_count == $replicas)
      and (.ready_pod_count == $replicas)
      and (all(.pods[]?; valid_pod))
      and ((.pods | map(.uid) | unique | length) == $replicas)
      and ((.pods | map(.node) | unique | length) >= $replicas)
      and (. as $snapshot
        | all($snapshot.pods[]?;
            .node as $node
            | ($snapshot.eligible_node_names | index($node)) != null))
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

java_placement_serving_path_has_symlink_component() {
  local candidate="$1"

  while [[ "$candidate" != "/" && "$candidate" != "." && -n "$candidate" ]]; do
    [[ -L "$candidate" ]] && return 0
    [[ "$candidate" == */* ]] || break
    candidate="${candidate%/*}"
    [[ -n "$candidate" ]] || candidate=.
  done
  return 1
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

java_placement_serving_report_evidence_files_are_valid() {
  local report_file="$1"
  local report_dir raw_report_dir references kind file expected_digest expected_replicas path actual_digest
  local target_pod target_uid target_node
  local -a health_files

  [[ -s "$report_file" && ! -L "$report_file" ]] || return 1
  java_placement_serving_path_has_symlink_component "$report_file" && return 1
  raw_report_dir="${report_file%/*}"
  [[ "$raw_report_dir" != "$report_file" ]] || raw_report_dir=.
  [[ ! -L "$raw_report_dir" ]] || return 1
  report_dir="$raw_report_dir"
  report_dir="$(cd -- "$report_dir" && pwd)" || return 1
  [[ -d "$report_dir/placement" && ! -L "$report_dir/placement" &&
    -d "$report_dir/health" && ! -L "$report_dir/health" ]] || return 1
  health_files=(
    "$report_dir/health/baseline-readiness.json"
    "$report_dir/health/baseline-liveness.json"
    "$report_dir/health/redis-outage-readiness.json"
    "$report_dir/health/redis-outage-liveness.json"
    "$report_dir/health/restored-readiness.json"
    "$report_dir/health/restored-liveness.json"
    "$report_dir/health/baseline-serving.json"
    "$report_dir/health/redis-outage-serving.json"
    "$report_dir/health/restored-serving.json"
  )
  references="$(jq -er \
    --argjson expected_files "$JAVA_PLACEMENT_SERVING_EVIDENCE_FILES_JSON" '
    [
      (.placement.snapshots | to_entries[]
       | ["placement", .value.file, .value.sha256, ""]),
      (.redis_outage.snapshots | to_entries[]
       | ["redis", .value.file, .value.sha256, (.value.replicas | tostring)]),
      (.observations | to_entries[] | .value | to_entries[] | .value
       | ["health", .body_file, .body_sha256, ""])
    ] as $references
    | if ($references | length) == ($expected_files | length)
      and (($references | map(.[1]) | unique | length) == ($expected_files | length))
      and (($references | map(.[1]) | sort) == ($expected_files | sort))
      then ($references[] | @tsv)
      else empty
      end
  ' "$report_file")" || return 1

  IFS=$'\t' read -r target_pod target_uid target_node < <(
    jq -er '[.target.pod, .target.pod_uid, .target.node] | @tsv' "$report_file"
  ) || return 1

  while IFS=$'\t' read -r kind file expected_digest expected_replicas; do
    [[ "$file" != /* && "$file" != *..* ]] || return 1
    case "$kind:$file" in
      placement:placement/*.json|redis:placement/redis-*.json|health:health/*.json) ;;
      *) return 1 ;;
    esac
    path="$report_dir/$file"
    [[ -f "$path" && ! -L "$path" ]] || return 1
    actual_digest="$(java_placement_serving_sha256_digest "$path")" || return 1
    [[ "$actual_digest" == "$expected_digest" ]] || return 1
    case "$kind" in
      placement)
        java_placement_serving_snapshot_file_is_ready "$path" || return 1
        if [[ "$file" == placement/baseline.json ]]; then
          jq -e --arg pod "$target_pod" --arg uid "$target_uid" --arg node "$target_node" '
            any(.pods[]?; .name == $pod and .uid == $uid and .node == $node)
          ' "$path" >/dev/null || return 1
        fi
        ;;
      redis) java_placement_serving_redis_snapshot_is_expected \
        "$path" "$expected_replicas" || return 1 ;;
    esac
  done <<<"$references"

  jq -e -s '
    length == 9
    and all(.[0:6][]; type == "object" and .status == "UP")
    and all(.[6:9][]; type == "object")
  ' "${health_files[@]}" >/dev/null || return 1

  jq -e \
    --slurpfile baseline "$report_dir/placement/baseline.json" \
    --slurpfile outage "$report_dir/placement/redis-outage.json" \
    --slurpfile restored "$report_dir/placement/restored.json" '
      def identities($snapshot):
        [$snapshot.pods[] | {name, uid}] | sort_by(.name);
      def restarts($snapshot):
        [$snapshot.pods[] | {name, restart_count}] | sort_by(.name);
      (.placement.pod_uid_unchanged == (
        identities($baseline[0]) == identities($outage[0])
        and identities($baseline[0]) == identities($restored[0])
      ))
      and (.placement.restart_count_unchanged == (
        restarts($baseline[0]) == restarts($outage[0])
        and restarts($baseline[0]) == restarts($restored[0])
      ))
    ' "$report_file" >/dev/null || return 1
}

java_placement_serving_write_pass_report() {
  local evidence_dir="$1"
  local observed_seconds="$2"
  local report_file="$evidence_dir/java-placement-serving.json"
  local temporary_file
  local baseline_digest outage_digest restored_digest
  local redis_before_digest redis_during_digest redis_after_digest

  [[ -d "$evidence_dir" ]] || return 1
  [[ "$observed_seconds" =~ ^[1-9][0-9]*$ ]] || return 1
  (( observed_seconds >= JAVA_PLACEMENT_SERVING_MIN_OBSERVE_SECONDS )) || return 1

  baseline_digest="$(java_placement_serving_sha256_digest \
    "$evidence_dir/placement/baseline.json")" || return 1
  outage_digest="$(java_placement_serving_sha256_digest \
    "$evidence_dir/placement/redis-outage.json")" || return 1
  restored_digest="$(java_placement_serving_sha256_digest \
    "$evidence_dir/placement/restored.json")" || return 1
  redis_before_digest="$(java_placement_serving_sha256_digest \
    "$evidence_dir/placement/redis-before.json")" || return 1
  redis_during_digest="$(java_placement_serving_sha256_digest \
    "$evidence_dir/placement/redis-during.json")" || return 1
  redis_after_digest="$(java_placement_serving_sha256_digest \
    "$evidence_dir/placement/redis-after.json")" || return 1

  temporary_file="$(mktemp "$evidence_dir/.java-placement-serving.XXXXXX")" || return 1
  if ! jq -n \
    --arg profile "$JAVA_PLACEMENT_SERVING_PROFILE" \
    --arg cluster "$JAVA_PLACEMENT_SERVING_CLUSTER" \
    --arg context "$JAVA_PLACEMENT_SERVING_CONTEXT" \
    --arg service "$JAVA_PLACEMENT_SERVING_SERVICE" \
    --arg container "$JAVA_PLACEMENT_SERVING_CONTAINER" \
    --arg redis_deployment "$JAVA_PLACEMENT_SERVING_REDIS_DEPLOYMENT" \
    --argjson port "$JAVA_PLACEMENT_SERVING_PORT" \
    --argjson replicas "$JAVA_PLACEMENT_SERVING_REPLICAS" \
    --arg readiness_path "$JAVA_PLACEMENT_SERVING_READINESS_PATH" \
    --arg liveness_path "$JAVA_PLACEMENT_SERVING_LIVENESS_PATH" \
    --arg application_path "$JAVA_PLACEMENT_SERVING_APPLICATION_PATH" \
    --argjson claim_boundary "$JAVA_PLACEMENT_SERVING_CLAIM_BOUNDARY_JSON" \
    --slurpfile provenance "$evidence_dir/provenance.json" \
    --slurpfile baseline "$evidence_dir/placement/baseline.json" \
    --slurpfile outage "$evidence_dir/placement/redis-outage.json" \
    --slurpfile restored "$evidence_dir/placement/restored.json" \
    --slurpfile redis_before "$evidence_dir/placement/redis-before.json" \
    --slurpfile redis_during "$evidence_dir/placement/redis-during.json" \
    --slurpfile redis_after "$evidence_dir/placement/redis-after.json" \
    --slurpfile baseline_health "$evidence_dir/health/baseline.json" \
    --slurpfile outage_health "$evidence_dir/health/redis-outage.json" \
    --slurpfile restored_health "$evidence_dir/health/restored.json" \
    --arg baseline_digest "$baseline_digest" \
    --arg outage_digest "$outage_digest" \
    --arg restored_digest "$restored_digest" \
    --arg redis_before_digest "$redis_before_digest" \
    --arg redis_during_digest "$redis_during_digest" \
    --arg redis_after_digest "$redis_after_digest" \
    --argjson observed_seconds "$observed_seconds" '
      def identities($snapshot):
        [$snapshot.pods[] | {name, uid}] | sort_by(.name);
      def restarts($snapshot):
        [$snapshot.pods[] | {name, restart_count}] | sort_by(.name);
      ($baseline[0]) as $baseline_snapshot
      | ($outage[0]) as $outage_snapshot
      | ($restored[0]) as $restored_snapshot
      | ($baseline_snapshot.pods | sort_by(.name) | .[0]) as $target
      | {
          schema_version: 1,
          profile: $profile,
          status: "PASS",
          cluster: $cluster,
          context: $context,
          namespace: ($provenance[0].namespace // ""),
          namespace_run_id: ($provenance[0].namespace_run_id // ""),
          source_revision: ($provenance[0].source_revision // ""),
          target: {
            service: $service,
            container: $container,
            port: $port,
            pod: ($target.name // ""),
            pod_uid: ($target.uid // ""),
            node: ($target.node // ""),
            replicas: $replicas
          },
          placement: {
            node_pool: ($baseline_snapshot.node_pool // ""),
            pod_count: ($baseline_snapshot.pod_count // 0),
            ready_pod_count: ($baseline_snapshot.ready_pod_count // 0),
            distinct_nodes: ($baseline_snapshot.distinct_nodes // 0),
            pod_uid_unchanged: (
              identities($baseline_snapshot) == identities($outage_snapshot)
              and identities($baseline_snapshot) == identities($restored_snapshot)
            ),
            restart_count_unchanged: (
              restarts($baseline_snapshot) == restarts($outage_snapshot)
              and restarts($baseline_snapshot) == restarts($restored_snapshot)
            ),
            snapshots: {
              baseline: {file:"placement/baseline.json", sha256:$baseline_digest},
              outage: {file:"placement/redis-outage.json", sha256:$outage_digest},
              restored: {file:"placement/restored.json", sha256:$restored_digest}
            }
          },
          observations: {
            baseline: $baseline_health[0],
            redis_outage: $outage_health[0],
            restored: $restored_health[0]
          },
          redis_outage: {
            deployment: $redis_deployment,
            replicas_before: ($redis_before[0].desired_replicas // -1),
            replicas_during: ($redis_during[0].desired_replicas // -1),
            replicas_after: ($redis_after[0].desired_replicas // -1),
            observed_seconds: $observed_seconds,
            snapshots: {
              before: {file:"placement/redis-before.json", sha256:$redis_before_digest,
                replicas:($redis_before[0].desired_replicas // -1)},
              during: {file:"placement/redis-during.json", sha256:$redis_during_digest,
                replicas:($redis_during[0].desired_replicas // -1)},
              after: {file:"placement/redis-after.json", sha256:$redis_after_digest,
                replicas:($redis_after[0].desired_replicas // -1)}
            }
          },
          claim_boundary: $claim_boundary
        }
    ' >"$temporary_file"; then
    rm -f -- "$temporary_file"
    return 1
  fi
  mv -- "$temporary_file" "$report_file" || {
    rm -f -- "$temporary_file"
    return 1
  }
  java_placement_serving_report_is_passed "$report_file"
}

java_placement_serving_report_is_passed() {
  local report_file="$1"

  [[ -s "$report_file" ]] || return 1
  jq -e \
    --arg profile "$JAVA_PLACEMENT_SERVING_PROFILE" \
    --arg cluster "$JAVA_PLACEMENT_SERVING_CLUSTER" \
    --arg context "$JAVA_PLACEMENT_SERVING_CONTEXT" \
    --arg service "$JAVA_PLACEMENT_SERVING_SERVICE" \
    --arg container "$JAVA_PLACEMENT_SERVING_CONTAINER" \
    --arg redis_deployment "$JAVA_PLACEMENT_SERVING_REDIS_DEPLOYMENT" \
    --arg node_pool "$JAVA_PLACEMENT_SERVING_NODE_POOL" \
    --arg readiness_path "$JAVA_PLACEMENT_SERVING_READINESS_PATH" \
    --arg liveness_path "$JAVA_PLACEMENT_SERVING_LIVENESS_PATH" \
    --arg application_path "$JAVA_PLACEMENT_SERVING_APPLICATION_PATH" \
    --argjson port "$JAVA_PLACEMENT_SERVING_PORT" \
    --argjson replicas "$JAVA_PLACEMENT_SERVING_REPLICAS" \
    --argjson min_observe_seconds "$JAVA_PLACEMENT_SERVING_MIN_OBSERVE_SECONDS" \
    --argjson claim_boundary "$JAVA_PLACEMENT_SERVING_CLAIM_BOUNDARY_JSON" '
      def text: type == "string" and length > 0;
      def digest: type == "string" and test("^sha256:[0-9a-f]{64}$");
      def healthy_probe($probe; $expected_path):
        ($probe.http_status == 200)
        and ($probe.path == $expected_path)
        and ($probe.body_status == "UP")
        and ($probe.body_file | text)
        and ($probe.body_sha256 | digest);
      def healthy_serving($probe):
        ($probe.http_status == 200)
        and ($probe.path == $application_path)
        and ($probe.body_type == "object")
        and ($probe.body_file | text)
        and ($probe.body_sha256 | digest);
      def healthy_observation($observation):
        healthy_probe($observation.readiness; $readiness_path)
        and healthy_probe($observation.liveness; $liveness_path)
        and healthy_serving($observation.serving);
      def identity_contract:
        (.schema_version == 1)
        and (.profile == $profile)
        and (.status == "PASS")
        and (.cluster == $cluster)
        and (.context == $context)
        and (.namespace | text)
        and (.namespace_run_id | text)
        and (.source_revision | type == "string" and test("^[0-9a-f]{40}$"));
      def target_contract:
        (.target.service == $service)
        and (.target.container == $container)
        and (.target.port == $port)
        and (.target.replicas == $replicas)
        and (.target.pod | text)
        and (.target.pod_uid | text)
        and (.target.node | text);
      def placement_contract:
        (.placement.node_pool == $node_pool)
        and (.placement.pod_count == $replicas)
        and (.placement.ready_pod_count == $replicas)
        and (.placement.distinct_nodes >= $replicas)
        and (.placement.pod_uid_unchanged == true)
        and (.placement.restart_count_unchanged == true);
      identity_contract
      and target_contract
      and placement_contract
      and healthy_observation(.observations.baseline)
      and healthy_observation(.observations.redis_outage)
      and healthy_observation(.observations.restored)
      and (.redis_outage.deployment == $redis_deployment)
      and (.redis_outage.replicas_before == 1)
      and (.redis_outage.replicas_during == 0)
      and (.redis_outage.replicas_after == 1)
      and (.redis_outage.observed_seconds >= $min_observe_seconds)
      and (.claim_boundary == $claim_boundary)
    ' "$report_file" >/dev/null || return 1
  java_placement_serving_report_evidence_files_are_valid "$report_file"
}

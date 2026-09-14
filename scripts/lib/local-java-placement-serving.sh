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
JAVA_PLACEMENT_SERVING_LIVENESS_INITIAL_DELAY_SECONDS=20
JAVA_PLACEMENT_SERVING_LIVENESS_PERIOD_SECONDS=10
JAVA_PLACEMENT_SERVING_LIVENESS_FAILURE_THRESHOLD=3
JAVA_PLACEMENT_SERVING_CLAIM_BOUNDARY_JSON='[
  "source-aligned query-service placement and serving",
  "query-service readiness and liveness remained healthy during a Redis outage",
  "no query-service Pod replacement or restart was observed during the bounded outage",
  "diagnostic-only evidence; not a full-local aggregate certification"
]'
# The outage may begin just after a Pod starts. Cover the configured liveness
# initial delay, three failure periods, and one extra period for detection.
JAVA_PLACEMENT_SERVING_MIN_OBSERVE_SECONDS=$((
  JAVA_PLACEMENT_SERVING_LIVENESS_INITIAL_DELAY_SECONDS
  + JAVA_PLACEMENT_SERVING_LIVENESS_PERIOD_SECONDS *
    JAVA_PLACEMENT_SERVING_LIVENESS_FAILURE_THRESHOLD
  + JAVA_PLACEMENT_SERVING_LIVENESS_PERIOD_SECONDS
))

java_placement_serving_probe_contract_is_valid() {
  local deployment_file="$1"

  [[ -s "$deployment_file" ]] || return 1
  jq -e \
    --arg container "$JAVA_PLACEMENT_SERVING_CONTAINER" \
    --arg startup_path "$JAVA_PLACEMENT_SERVING_STARTUP_PATH" \
    --arg readiness_path "$JAVA_PLACEMENT_SERVING_READINESS_PATH" \
    --arg liveness_path "$JAVA_PLACEMENT_SERVING_LIVENESS_PATH" \
    --arg port http \
    --argjson liveness_initial_delay "$JAVA_PLACEMENT_SERVING_LIVENESS_INITIAL_DELAY_SECONDS" \
    --argjson liveness_period "$JAVA_PLACEMENT_SERVING_LIVENESS_PERIOD_SECONDS" \
    --argjson liveness_failure_threshold "$JAVA_PLACEMENT_SERVING_LIVENESS_FAILURE_THRESHOLD" \
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
           and $liveness.httpGet.port == $port
           and $liveness.initialDelaySeconds == $liveness_initial_delay
           and $liveness.periodSeconds == $liveness_period
           and $liveness.failureThreshold == $liveness_failure_threshold)
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
               ready: any(.status.conditions[]?;
                 .type == "Ready" and .status == "True"),
               restart_count: ($containerStatus.restartCount // -1),
               image_id: ($containerStatus.imageID // "")
             }
         ] | sort_by(.name)) as $podRows
      | ([ $nodeItems[]?
           | select(.metadata.labels["simplematch.io/node-pool"] == $node_pool)
           | .metadata.name ] | unique) as $eligibleNodes
      | ($deploymentObject.spec // {}) as $deploymentSpec
      | ([ $deploymentSpec.template.spec.containers[]?
           | select(.name == $container)]
         | if length == 1 then .[0] else {} end) as $deploymentContainer
      | {
          deployment_name: ($deploymentObject.metadata.name // ""),
          deployment_image: ($deploymentContainer.image // ""),
          desired_replicas: ($deploymentSpec.replicas // 0),
          deployment_node_pool: (
            $deploymentSpec.template.spec.nodeSelector["simplematch.io/node-pool"] // ""
          ),
          eligible_node_names: $eligibleNodes,
          pods: $podRows
      }
    '
}

# Validate only the normalized shape; Kubernetes API traversal stays above.
java_placement_serving_snapshot_json_is_ready() {
  local snapshot_json="$1"

  jq -e -s \
    --arg service "$JAVA_PLACEMENT_SERVING_SERVICE" \
    --arg node_pool "$JAVA_PLACEMENT_SERVING_NODE_POOL" \
    --argjson replicas "$JAVA_PLACEMENT_SERVING_REPLICAS" '
      def text: type == "string" and length > 0;
      def image_digest:
        capture("@(?<digest>sha256:[0-9a-f]{64})$").digest;
      def valid_pod:
        (.name | text)
        and (.uid | text)
        and (.node | text)
        and (.ready == true)
        and (.restart_count | numbers and . >= 0);
      length == 1
      and (
        .[0] as $snapshot
        | (try ($snapshot.deployment_image | image_digest) catch "") as $deployment_digest
        | ($snapshot.deployment_name == $service)
        and ($deployment_digest | text)
        and ($snapshot.deployment_node_pool == $node_pool)
        and ($snapshot.desired_replicas == $replicas)
        and (($snapshot.pods | length) == $replicas)
        and (all($snapshot.pods[]?; valid_pod))
        and (all($snapshot.pods[]?;
            ((.image_id // "") | endswith("@" + $deployment_digest))))
        and (($snapshot.pods | map(.node) | unique | length) == $replicas)
        and (all($snapshot.pods[]?;
            .node as $node
            | ($snapshot.eligible_node_names | index($node)) != null))
      )
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
  jq -e -s --argjson expected "$expected_replicas" '
    length == 1
    and (
      .[0] as $snapshot
      | ($snapshot.deployment_name == "redis")
      and ($snapshot.desired_replicas == $expected)
      and if $expected == 0
          then ($snapshot.pods | all(.[]; .ready != true))
          else ($snapshot.pods | any(.[]; .ready == true))
          end
    )
  ' "$snapshot_file" >/dev/null
}

java_placement_serving_report_evidence_files_are_valid() {
  local report_file="$1"
  local report_dir raw_report_dir references kind file expected_digest expected_replicas path actual_digest

  [[ -s "$report_file" && ! -L "$report_file" ]] || return 1
  java_placement_serving_path_has_symlink_component "$report_file" && return 1
  raw_report_dir="${report_file%/*}"
  [[ "$raw_report_dir" != "$report_file" ]] || raw_report_dir=.
  [[ ! -L "$raw_report_dir" ]] || return 1
  report_dir="$raw_report_dir"
  report_dir="$(cd -- "$report_dir" && pwd)" || return 1
  [[ -d "$report_dir/placement" && ! -L "$report_dir/placement" &&
    -d "$report_dir/health" && ! -L "$report_dir/health" ]] || return 1
  [[ -f "$report_dir/provenance.json" && ! -L "$report_dir/provenance.json" ]] ||
    return 1
  references="$(jq -er -s '
    def evidence($kind; $file; $entry; $replicas):
      select($entry.file == $file)
      | [$kind, $file, $entry.sha256, $replicas];
    def health($file; $entry):
      evidence("health"; $file;
        {file: $entry.body_file, sha256: $entry.body_sha256}; "");
    select(length == 1)
    | .[0]
    | [
        evidence("placement"; "placement/baseline.json";
          .placement.snapshots.baseline; ""),
        evidence("placement"; "placement/redis-outage.json";
          .placement.snapshots.outage; ""),
        evidence("placement"; "placement/restored.json";
          .placement.snapshots.restored; ""),
        evidence("redis"; "placement/redis-before.json";
          .redis_outage.snapshots.before; "1"),
        evidence("redis"; "placement/redis-during.json";
          .redis_outage.snapshots.during; "0"),
        evidence("redis"; "placement/redis-after.json";
          .redis_outage.snapshots.after; "1"),
        health("health/baseline-readiness.json"; .observations.baseline.readiness),
        health("health/baseline-liveness.json"; .observations.baseline.liveness),
        health("health/baseline-serving.json"; .observations.baseline.serving),
        health("health/redis-outage-readiness.json";
          .observations.redis_outage.readiness),
        health("health/redis-outage-liveness.json";
          .observations.redis_outage.liveness),
        health("health/redis-outage-serving.json"; .observations.redis_outage.serving),
        health("health/restored-readiness.json"; .observations.restored.readiness),
        health("health/restored-liveness.json"; .observations.restored.liveness),
        health("health/restored-serving.json"; .observations.restored.serving)
      ]
    | select(length == 15)
    | .[]
    | @tsv
  ' "$report_file")" || return 1

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
        ;;
      redis) java_placement_serving_redis_snapshot_is_expected \
        "$path" "$expected_replicas" || return 1 ;;
      health)
        case "$file" in
          *-readiness.json|*-liveness.json)
            jq -e -s 'length == 1 and .[0].status == "UP"' \
              "$path" >/dev/null || return 1
            ;;
          *-serving.json)
            jq -e -s 'length == 1 and (.[0] | type) == "object"' \
              "$path" >/dev/null || return 1
            ;;
        esac
        ;;
    esac
  done <<<"$references"

  jq -e \
    --slurpfile provenance "$report_dir/provenance.json" \
    --slurpfile baseline "$report_dir/placement/baseline.json" \
    --slurpfile outage "$report_dir/placement/redis-outage.json" \
    --slurpfile restored "$report_dir/placement/restored.json" '
      def identities($snapshot):
        [$snapshot.pods[] | {name, uid, node}] | sort_by(.name);
      def restarts($snapshot):
        [$snapshot.pods[] | {name, restart_count}] | sort_by(.name);
      .target as $target
      | ($provenance | length == 1)
      and (.source_revision == $provenance[0].source_revision)
      and (.namespace == $provenance[0].namespace)
      and (.namespace_run_id == $provenance[0].namespace_run_id)
      and (.placement.node_pool == $baseline[0].deployment_node_pool)
      and (.placement.pod_count == ($baseline[0].pods | length))
      and (.placement.ready_pod_count ==
        ([$baseline[0].pods[] | select(.ready == true)] | length))
      and (.placement.distinct_nodes ==
        ([$baseline[0].pods[].node] | unique | length))
      and ($baseline[0].deployment_image == $outage[0].deployment_image)
      and ($baseline[0].deployment_image == $restored[0].deployment_image)
      and any($baseline[0].pods[]?;
        .name == $target.pod
        and .uid == $target.pod_uid
        and .node == $target.node)
      and (.placement.pod_uid_unchanged == (
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
        [$snapshot.pods[] | {name, uid, node}] | sort_by(.name);
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
            node: ($target.node // "")
          },
          placement: {
            node_pool: ($baseline_snapshot.deployment_node_pool // ""),
            pod_count: ($baseline_snapshot.pods | length),
            ready_pod_count: (
              [$baseline_snapshot.pods[] | select(.ready == true)] | length
            ),
            distinct_nodes: (
              [$baseline_snapshot.pods[].node] | unique | length
            ),
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
              before: {file:"placement/redis-before.json", sha256:$redis_before_digest},
              during: {file:"placement/redis-during.json", sha256:$redis_during_digest},
              after: {file:"placement/redis-after.json", sha256:$redis_after_digest}
            }
          },
          claim_boundary: $claim_boundary
        }
    ' >"$temporary_file"; then
    rm -f -- "$temporary_file"
    return 1
  fi
  java_placement_serving_report_is_passed "$temporary_file" || {
    rm -f -- "$temporary_file"
    return 1
  }
  mv -- "$temporary_file" "$report_file" || {
    rm -f -- "$temporary_file"
    return 1
  }
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
      def healthy_probe($probe; $expected_path):
        ($probe.http_status == 200)
        and ($probe.path == $expected_path)
        and ($probe.body_status == "UP");
      def healthy_serving($probe):
        ($probe.http_status == 200)
        and ($probe.path == $application_path)
        and ($probe.body_status == "SERVING");
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
        and (.target.port == $port);
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

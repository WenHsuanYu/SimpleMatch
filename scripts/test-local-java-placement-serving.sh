#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-java-placement-serving.sh
source "$script_dir/lib/local-java-placement-serving.sh"

fail() {
  printf 'Java placement/serving contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-java-placement-serving.XXXXXX")"
trap 'rm -rf -- "$fixture_dir"' EXIT

deployment="$fixture_dir/deployment.json"
pods="$fixture_dir/pods.json"
nodes="$fixture_dir/nodes.json"
report="$fixture_dir/java-placement-serving.json"

jq -n '
  {
    metadata: {name: "query-service"},
    spec: {
      replicas: 2,
      template: {
        spec: {
          nodeSelector: {"simplematch.io/node-pool": "local-resilience"},
          containers: [{
            name: "query-service",
            ports: [{name: "http", containerPort: 8086}],
            startupProbe: {httpGet: {path: "/actuator/health/readiness", port: "http"}},
            readinessProbe: {httpGet: {path: "/actuator/health/readiness", port: "http"}},
            livenessProbe: {httpGet: {path: "/actuator/health/liveness", port: "http"}}
          }]
        }
      }
    },
    status: {readyReplicas: 2}
  }
' >"$deployment"

jq -n '
  {
    items: [
      {
        metadata: {
          name: "query-service-a", uid: "pod-query-a",
          labels: {"app.kubernetes.io/name": "query-service", "app.kubernetes.io/component": "java-service"}
        },
        spec: {nodeName: "simplematch-live-worker"},
        status: {
          phase: "Running",
          conditions: [{type: "Ready", status: "True"}],
          containerStatuses: [{name: "query-service", restartCount: 0,
            imageID: "docker-pullable://simplematch/query-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
        }
      },
      {
        metadata: {
          name: "query-service-b", uid: "pod-query-b",
          labels: {"app.kubernetes.io/name": "query-service", "app.kubernetes.io/component": "java-service"}
        },
        spec: {nodeName: "simplematch-live-worker2"},
        status: {
          phase: "Running",
          conditions: [{type: "Ready", status: "True"}],
          containerStatuses: [{name: "query-service", restartCount: 0,
            imageID: "docker-pullable://simplematch/query-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
        }
      }
    ]
  }
' >"$pods"

jq -n '
  {
    items: [
      {metadata: {name: "simplematch-live-worker", labels: {"simplematch.io/node-pool": "local-resilience"}}},
      {metadata: {name: "simplematch-live-worker2", labels: {"simplematch.io/node-pool": "local-resilience"}}},
      {metadata: {name: "simplematch-live-control-plane", labels: {"simplematch.io/node-pool": "control-plane"}}}
    ]
  }
' >"$nodes"

java_placement_serving_probe_contract_is_valid "$deployment" ||
  fail 'valid startup/readiness/liveness probes were rejected'
java_placement_serving_runtime_snapshot_is_ready "$deployment" "$pods" "$nodes" ||
  fail 'valid two-Pod placement snapshot was rejected'
snapshot_json="$(java_placement_serving_runtime_snapshot "$deployment" "$pods" "$nodes")" ||
  fail 'runtime snapshot could not be normalized'
jq -e '.pod_count == 2 and .ready_pod_count == 2 and .distinct_nodes == 2' \
  <<<"$snapshot_json" >/dev/null ||
  fail 'normalized runtime snapshot omitted canonical placement counts'

jq '.spec.template.spec.containers[0].livenessProbe.httpGet.path = "/healthz"' \
  "$deployment" >"$fixture_dir/deployment-wrong-probe.json"
if java_placement_serving_probe_contract_is_valid "$fixture_dir/deployment-wrong-probe.json"; then
  fail 'wrong liveness probe path was accepted'
fi

jq '.items[1].metadata.uid = .items[0].metadata.uid' "$pods" \
  >"$fixture_dir/pods-duplicate-uid.json"
if java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-duplicate-uid.json" "$nodes"; then
  fail 'duplicate Pod UID was accepted'
fi

jq '.items[1].spec.nodeName = .items[0].spec.nodeName' "$pods" \
  >"$fixture_dir/pods-one-node.json"
if java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-one-node.json" "$nodes"; then
  fail 'same-node placement was accepted'
fi

jq '.node_pool = "wrong-pool"' <<<"$snapshot_json" \
  >"$fixture_dir/snapshot-wrong-node-pool.json"
if java_placement_serving_snapshot_file_is_ready \
  "$fixture_dir/snapshot-wrong-node-pool.json"; then
  fail 'snapshot with the wrong node pool was accepted'
fi

jq '.items[0].status.containerStatuses[0].imageID = "docker://query-service:latest"' "$pods" \
  >"$fixture_dir/pods-mutable-image.json"
if java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-mutable-image.json" "$nodes"; then
  fail 'mutable runtime image identity was accepted'
fi

jq '(.items[0].status.containerStatuses[0].name = "sidecar")' "$pods" \
  >"$fixture_dir/pods-without-target-container.json"
if java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-without-target-container.json" "$nodes"; then
  fail 'Pod without the target container was accepted'
fi

mkdir -p "$fixture_dir/placement" "$fixture_dir/health"
for placement_stage in baseline redis-outage restored; do
  printf '%s\n' "$snapshot_json" >"$fixture_dir/placement/${placement_stage}.json"
done

for redis_stage in before during after; do
  case "$redis_stage" in
    before|after) redis_replicas=1 ;;
    during) redis_replicas=0 ;;
  esac
  jq -n --arg stage "$redis_stage" --argjson replicas "$redis_replicas" '
    {
      stage: $stage, deployment_name: "redis", desired_replicas: $replicas,
      ready_replicas: $replicas, ready_pod_count: $replicas,
      pods: (if $replicas == 1 then
        [{name:"redis-0",uid:"redis-pod",node:"simplematch-live-worker",
          phase:"Running",ready:true,restart_count:0,
          image_id:"docker-pullable://redis@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]
        else [] end)
    }
  ' >"$fixture_dir/placement/redis-${redis_stage}.json"
done

for health_file in \
  baseline-readiness baseline-liveness redis-outage-readiness redis-outage-liveness \
  restored-readiness restored-liveness; do
  printf '%s\n' '{"status":"UP"}' >"$fixture_dir/health/${health_file}.json"
done
for serving_file in baseline-serving redis-outage-serving restored-serving; do
  printf '%s\n' '{"partitions":[]}' >"$fixture_dir/health/${serving_file}.json"
done

write_health_stage() {
  local stage="$1"
  local readiness_digest liveness_digest serving_digest

  readiness_digest="$(java_placement_serving_sha256_digest \
    "$fixture_dir/health/${stage}-readiness.json")"
  liveness_digest="$(java_placement_serving_sha256_digest \
    "$fixture_dir/health/${stage}-liveness.json")"
  serving_digest="$(java_placement_serving_sha256_digest \
    "$fixture_dir/health/${stage}-serving.json")"
  jq -n \
    --arg readiness_file "health/${stage}-readiness.json" \
    --arg liveness_file "health/${stage}-liveness.json" \
    --arg serving_file "health/${stage}-serving.json" \
    --arg readiness_digest "$readiness_digest" \
    --arg liveness_digest "$liveness_digest" \
    --arg serving_digest "$serving_digest" '
      {
        readiness: {path:"/actuator/health/readiness", http_status:200,
          body_status:"UP", body_type:"object", body_file:$readiness_file,
          body_sha256:$readiness_digest},
        liveness: {path:"/actuator/health/liveness", http_status:200,
          body_status:"UP", body_type:"object", body_file:$liveness_file,
          body_sha256:$liveness_digest},
        serving: {path:"/api/v1/freshness", http_status:200,
          body_status:"SERVING", body_type:"object", body_file:$serving_file,
          body_sha256:$serving_digest}
      }
    ' >"$fixture_dir/health/${stage}.json"
}

write_health_stage baseline
write_health_stage redis-outage
write_health_stage restored

jq -n \
  --arg namespace "simplematch-java-run" \
  --arg run_id "run-1" \
  --arg source_revision "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  '{namespace:$namespace,namespace_run_id:$run_id,managed_by:"local-resilience",source_revision:$source_revision}' \
  >"$fixture_dir/provenance.json"

java_placement_serving_write_pass_report "$fixture_dir" "$JAVA_PLACEMENT_SERVING_MIN_OBSERVE_SECONDS" ||
  fail 'report assembler did not produce a valid PASS report'
[[ -s "$report" ]] || fail 'report assembler did not publish the report'

ln -s "$fixture_dir" "$fixture_dir-alias"
if java_placement_serving_report_is_passed "$fixture_dir-alias/java-placement-serving.json"; then
  fail 'report through a symlinked parent path was accepted'
fi
rm "$fixture_dir-alias"

cp "$fixture_dir/placement/baseline.json" "$fixture_dir/baseline-placement.original"
printf '%s\n' '{}' >"$fixture_dir/placement/baseline.json"
if java_placement_serving_report_is_passed "$report"; then
  fail 'tampered linked placement evidence was accepted'
fi
mv "$fixture_dir/baseline-placement.original" "$fixture_dir/placement/baseline.json"

cp "$fixture_dir/placement/redis-during.json" "$fixture_dir/redis-during.original"
jq '.desired_replicas = 1' "$fixture_dir/placement/redis-during.json" \
  >"$fixture_dir/placement/redis-during.tampered.json"
mv "$fixture_dir/placement/redis-during.tampered.json" "$fixture_dir/placement/redis-during.json"
if java_placement_serving_report_is_passed "$report"; then
  fail 'tampered linked Redis evidence was accepted'
fi
mv "$fixture_dir/redis-during.original" "$fixture_dir/placement/redis-during.json"

cp "$fixture_dir/health/baseline-serving.json" "$fixture_dir/baseline-serving.original"
printf '%s\n' '{"partitions":[{"tampered":true}]}' >"$fixture_dir/health/baseline-serving.json"
if java_placement_serving_report_is_passed "$report"; then
  fail 'tampered linked serving evidence was accepted'
fi
mv "$fixture_dir/baseline-serving.original" "$fixture_dir/health/baseline-serving.json"

cp "$fixture_dir/health/baseline-readiness.json" "$fixture_dir/baseline-readiness.original"
printf '%s\n' '{"status":"DOWN"}' >"$fixture_dir/health/baseline-readiness.json"
invalid_readiness_digest="$(java_placement_serving_sha256_digest \
  "$fixture_dir/health/baseline-readiness.json")"
jq --arg digest "$invalid_readiness_digest" \
  '.observations.baseline.readiness.body_sha256 = $digest' "$report" \
  >"$fixture_dir/report-invalid-readiness-body.json"
if java_placement_serving_report_is_passed \
  "$fixture_dir/report-invalid-readiness-body.json"; then
  fail 'invalid linked readiness body was accepted after its digest was updated'
fi
mv "$fixture_dir/baseline-readiness.original" "$fixture_dir/health/baseline-readiness.json"

cp "$fixture_dir/placement/redis-outage.json" "$fixture_dir/redis-outage.original"
jq '.pods[0].uid = "replacement-uid"' \
  "$fixture_dir/placement/redis-outage.json" \
  >"$fixture_dir/placement/redis-outage.tampered.json"
mv "$fixture_dir/placement/redis-outage.tampered.json" \
  "$fixture_dir/placement/redis-outage.json"
tampered_outage_digest="$(java_placement_serving_sha256_digest \
  "$fixture_dir/placement/redis-outage.json")"
jq --arg digest "$tampered_outage_digest" \
  '.placement.snapshots.outage.sha256 = $digest' "$report" \
  >"$fixture_dir/report-identity-mismatch.json"
if java_placement_serving_report_is_passed "$fixture_dir/report-identity-mismatch.json"; then
  fail 'cross-stage Pod identity mismatch was accepted after its digest was updated'
fi
mv "$fixture_dir/redis-outage.original" "$fixture_dir/placement/redis-outage.json"

jq '.placement.restart_count_unchanged = false' "$report" \
  >"$fixture_dir/report-restarted.json"
if java_placement_serving_report_is_passed "$fixture_dir/report-restarted.json"; then
  fail 'report with a restart was accepted'
fi

jq '.observations.redis_outage.liveness.http_status = 503' "$report" \
  >"$fixture_dir/report-liveness-failed.json"
if java_placement_serving_report_is_passed "$fixture_dir/report-liveness-failed.json"; then
  fail 'outage liveness failure was accepted as healthy'
fi

jq '.redis_outage.replicas_during = 1' "$report" \
  >"$fixture_dir/report-no-outage.json"
if java_placement_serving_report_is_passed "$fixture_dir/report-no-outage.json"; then
  fail 'report without a Redis outage was accepted'
fi

jq '.target.service = "account-service"' "$report" \
  >"$fixture_dir/report-wrong-target.json"
if java_placement_serving_report_is_passed "$fixture_dir/report-wrong-target.json"; then
  fail 'service-specific target outside the representative contract was accepted'
fi

jq '.target.pod = "query-service-b"' "$report" \
  >"$fixture_dir/report-mismatched-target.json"
if java_placement_serving_report_is_passed "$fixture_dir/report-mismatched-target.json"; then
  fail 'target identity unrelated to the baseline Pod was accepted'
fi

jq '.target.pod_uid = ""' "$report" \
  >"$fixture_dir/report-missing-target-identity.json"
if java_placement_serving_report_is_passed "$fixture_dir/report-missing-target-identity.json"; then
  fail 'report without target Pod identity was accepted'
fi

jq '.observations.restored.serving.body_file = "health/baseline-serving.json"' "$report" \
  >"$fixture_dir/report-duplicate-body-file.json"
if java_placement_serving_report_is_passed "$fixture_dir/report-duplicate-body-file.json"; then
  fail 'report with duplicate linked body evidence was accepted'
fi

jq '.claim_boundary = ["full-local certification passed"]' "$report" \
  >"$fixture_dir/report-overbroad-claim.json"
if java_placement_serving_report_is_passed "$fixture_dir/report-overbroad-claim.json"; then
  fail 'report with an overbroad claim boundary was accepted'
fi

jq '.observations.restored.serving.path = "/api/v1/orders"' "$report" \
  >"$fixture_dir/report-wrong-serving-path.json"
if java_placement_serving_report_is_passed "$fixture_dir/report-wrong-serving-path.json"; then
  fail 'wrong serving path was accepted'
fi

help_output="$("$script_dir"/run-local-java-placement-serving-check.sh --help)"
grep -Fq -- '--namespace-run-id' <<<"$help_output" ||
  fail 'runner help omitted namespace ownership input'
grep -Fq -- '--retained-evidence-dir' <<<"$help_output" ||
  fail 'runner help omitted retained provenance input'
grep -Fq 'diagnostic-only' <<<"$help_output" ||
  fail 'runner help omitted diagnostic-only boundary'
grep -Fq 'Redis' <<<"$help_output" ||
  fail 'runner help omitted bounded Redis outage'
grep -Fq '60 seconds' <<<"$help_output" ||
  fail 'runner help omitted the liveness-derived default window'
grep -Fq 'docker info' "$script_dir/run-local-java-placement-serving-check.sh" ||
  fail 'runner omitted Docker daemon preflight'
grep -Fq 'get clusters' "$script_dir/run-local-java-placement-serving-check.sh" ||
  fail 'runner omitted canonical kind preflight'
grep -Fq 'docker-system-df-before.txt' "$script_dir/run-local-java-placement-serving-check.sh" ||
  fail 'runner omitted pre-observation Docker inventory'
grep -Fq 'docker-system-df-after.txt' "$script_dir/run-local-java-placement-serving-check.sh" ||
  fail 'runner omitted post-observation Docker inventory'
grep -Fq 'java_placement_serving_write_pass_report' \
  "$script_dir/run-local-java-placement-serving-check.sh" ||
  fail 'runner omitted the tested report assembler seam'

for script in \
  "$script_dir/lib/local-java-placement-serving.sh" \
  "$script_dir/run-local-java-placement-serving-check.sh" \
  "$script_dir/test-local-java-placement-serving.sh"; do
  bash -n "$script"
done

printf 'Local Java placement/serving contract passed.\n'

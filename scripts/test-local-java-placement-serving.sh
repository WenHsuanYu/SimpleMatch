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
endpoint_slices="$fixture_dir/endpointslices.json"
report="$fixture_dir/report.json"

jq -n '
  {
    metadata: {name: "query-service", uid: "deployment-query", generation: 4},
    spec: {
      replicas: 2,
      template: {
        spec: {
          nodeSelector: {"simplematch.io/node-pool": "local-resilience"},
          containers: [{
            name: "query-service",
            image: "simplematch/query-service:test",
            ports: [{name: "http", containerPort: 8086}],
            startupProbe: {httpGet: {path: "/actuator/health/readiness", port: "http"}},
            readinessProbe: {httpGet: {path: "/actuator/health/readiness", port: "http"}},
            livenessProbe: {httpGet: {path: "/actuator/health/liveness", port: "http"}}
          }]
        }
      }
    },
    status: {readyReplicas: 2, availableReplicas: 2, updatedReplicas: 2}
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
          phase: "Running", podIP: "10.244.0.21",
          conditions: [{type: "Ready", status: "True"}],
          containerStatuses: [{name: "query-service", restartCount: 0, image: "simplematch/query-service:test",
            imageID: "docker-pullable://simplematch/query-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            state: {running: {startedAt: "2026-09-13T01:00:00Z"}}}]
        }
      },
      {
        metadata: {
          name: "query-service-b", uid: "pod-query-b",
          labels: {"app.kubernetes.io/name": "query-service", "app.kubernetes.io/component": "java-service"}
        },
        spec: {nodeName: "simplematch-live-worker2"},
        status: {
          phase: "Running", podIP: "10.244.0.22",
          conditions: [{type: "Ready", status: "True"}],
          containerStatuses: [{name: "query-service", restartCount: 0, image: "simplematch/query-service:test",
            imageID: "docker-pullable://simplematch/query-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            state: {running: {startedAt: "2026-09-13T01:00:01Z"}}}]
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

jq -n '
  {
    items: [{
      metadata: {name: "query-service-abc", labels: {"kubernetes.io/service-name": "query-service"}},
      endpoints: [
        {addresses: ["10.244.0.21"], conditions: {ready: true}},
        {addresses: ["10.244.0.22"], conditions: {ready: true}}
      ]
    }]
  }
' >"$endpoint_slices"

java_placement_serving_probe_contract_is_valid "$deployment" ||
  fail 'valid startup/readiness/liveness probes were rejected'
java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$pods" "$nodes" "$endpoint_slices" ||
  fail 'valid two-Pod placement snapshot was rejected'
snapshot_json="$(java_placement_serving_runtime_snapshot \
  "$deployment" "$pods" "$nodes" "$endpoint_slices")" ||
  fail 'runtime snapshot could not be normalized'
java_placement_serving_snapshot_json_is_ready "$snapshot_json" ||
  fail 'normalized runtime snapshot was rejected'
jq -e '.pod_count == 2 and .ready_endpoint_count == 2 and .distinct_nodes == 2' \
  <<<"$snapshot_json" >/dev/null ||
  fail 'normalized runtime snapshot omitted canonical placement counts'

jq '.spec.template.spec.nodeSelector["simplematch.io/node-pool"] = "other-pool"' \
  "$deployment" >"$fixture_dir/deployment-wrong-selector.json"
if java_placement_serving_runtime_snapshot_is_ready \
  "$fixture_dir/deployment-wrong-selector.json" "$pods" "$nodes" "$endpoint_slices"; then
  fail 'wrong canonical node selector was accepted'
fi

jq '.items[1].metadata.uid = .items[0].metadata.uid' "$pods" \
  >"$fixture_dir/pods-duplicate-uid.json"
if java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-duplicate-uid.json" "$nodes" "$endpoint_slices"; then
  fail 'duplicate Pod UID was accepted'
fi

jq '.items[0].metadata.name = ""' "$pods" \
  >"$fixture_dir/pods-missing-name.json"
if java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-missing-name.json" "$nodes" "$endpoint_slices"; then
  fail 'Pod with missing name was accepted'
fi

jq '.items[1].spec.nodeName = .items[0].spec.nodeName' "$pods" \
  >"$fixture_dir/pods-one-node.json"
if java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-one-node.json" "$nodes" "$endpoint_slices"; then
  fail 'same-node placement was accepted'
fi

jq '.items[0].endpoints[1].addresses = ["10.244.0.99"]' "$endpoint_slices" \
  >"$fixture_dir/endpointslices-wrong-address.json"
if java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$pods" "$nodes" "$fixture_dir/endpointslices-wrong-address.json"; then
  fail 'EndpointSlice address unrelated to a Pod was accepted'
fi

jq '.items[0].status.containerStatuses[0].imageID = "docker://query-service:latest"' "$pods" \
  >"$fixture_dir/pods-mutable-image.json"
if java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-mutable-image.json" "$nodes" "$endpoint_slices"; then
  fail 'mutable runtime image identity was accepted'
fi

jq '.spec.template.spec.containers[0].livenessProbe.httpGet.path = "/healthz"' \
  "$deployment" >"$fixture_dir/deployment-wrong-probe.json"
if java_placement_serving_probe_contract_is_valid "$fixture_dir/deployment-wrong-probe.json"; then
  fail 'wrong liveness probe path was accepted'
fi

mkdir -p "$fixture_dir/placement"
printf '%s\n' "$snapshot_json" >"$fixture_dir/placement/baseline.json"
printf '%s\n' "$snapshot_json" >"$fixture_dir/placement/redis-outage.json"
printf '%s\n' "$snapshot_json" >"$fixture_dir/placement/restored.json"

for redis_stage in before during after; do
  case "$redis_stage" in
    before|after) redis_replicas=1 ;;
    during) redis_replicas=0 ;;
  esac
  jq -n --arg stage "$redis_stage" --argjson replicas "$redis_replicas" '
    {
      stage: $stage, deployment_name: "redis", deployment_uid: "redis-deployment",
      desired_replicas: $replicas, ready_replicas: $replicas,
      available_replicas: $replicas, updated_replicas: $replicas,
      ready_pod_count: $replicas,
      pods: (if $replicas == 1 then
        [{name:"redis-0",uid:"redis-pod",node:"simplematch-live-worker",
          phase:"Running",ready:true,restart_count:0,
          image_id:"docker-pullable://redis@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]
        else [] end)
    }
  ' >"$fixture_dir/placement/redis-${redis_stage}.json"
done

mkdir -p "$fixture_dir/health"
for health_file in \
  baseline-readiness baseline-liveness redis-outage-readiness redis-outage-liveness \
  restored-readiness restored-liveness; do
  printf '%s\n' '{"status":"UP"}' >"$fixture_dir/health/${health_file}.json"
done
for serving_file in baseline-serving redis-outage-serving restored-serving; do
  printf '%s\n' '{"partitions":[]}' >"$fixture_dir/health/${serving_file}.json"
done
health_hash="sha256:$(sha256sum "$fixture_dir/health/baseline-readiness.json")"
health_hash="${health_hash%% *}"
serving_hash="sha256:$(sha256sum "$fixture_dir/health/baseline-serving.json")"
serving_hash="${serving_hash%% *}"
placement_baseline_hash="sha256:$(sha256sum "$fixture_dir/placement/baseline.json")"
placement_baseline_hash="${placement_baseline_hash%% *}"
placement_outage_hash="sha256:$(sha256sum "$fixture_dir/placement/redis-outage.json")"
placement_outage_hash="${placement_outage_hash%% *}"
placement_restored_hash="sha256:$(sha256sum "$fixture_dir/placement/restored.json")"
placement_restored_hash="${placement_restored_hash%% *}"
redis_before_hash="sha256:$(sha256sum "$fixture_dir/placement/redis-before.json")"
redis_before_hash="${redis_before_hash%% *}"
redis_during_hash="sha256:$(sha256sum "$fixture_dir/placement/redis-during.json")"
redis_during_hash="${redis_during_hash%% *}"
redis_after_hash="sha256:$(sha256sum "$fixture_dir/placement/redis-after.json")"
redis_after_hash="${redis_after_hash%% *}"
jq -n \
  --arg health_hash "$health_hash" --arg serving_hash "$serving_hash" \
  --arg placement_baseline_hash "$placement_baseline_hash" \
  --arg placement_outage_hash "$placement_outage_hash" \
  --arg placement_restored_hash "$placement_restored_hash" \
  --arg redis_before_hash "$redis_before_hash" \
  --arg redis_during_hash "$redis_during_hash" \
  --arg redis_after_hash "$redis_after_hash" '
  def health($path; $file): {
    path: $path, http_status: 200, body_status: "UP", body_type: "object",
    body_file: $file, body_sha256: $health_hash
  };
  def serving($file): {
    path: "/api/v1/freshness", http_status: 200, body_status: "SERVING",
    body_type: "object", body_file: $file, body_sha256: $serving_hash
  };
  {
    schema_version: 1,
    profile: "java-placement-serving",
    status: "PASS",
    cluster: "simplematch-live",
    context: "kind-simplematch-live",
    namespace: "simplematch-java-run",
    namespace_run_id: "run-1",
    source_revision: "0000000000000000000000000000000000000000",
    target: {
      service: "query-service", container: "query-service", port: 8086,
      pod: "query-service-a", pod_uid: "pod-query-a", node: "simplematch-live-worker", replicas: 2
    },
    placement: {
      node_pool: "local-resilience", pod_count: 2, ready_pod_count: 2,
      distinct_nodes: 2, ready_endpoint_count: 2, startup_completed_pod_count: 2,
      image_ids: ["docker-pullable://simplematch/query-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],
      pod_uid_unchanged: true, restart_count_unchanged: true,
      snapshots: {
        baseline: {file: "placement/baseline.json", sha256: $placement_baseline_hash},
        outage: {file: "placement/redis-outage.json", sha256: $placement_outage_hash},
        restored: {file: "placement/restored.json", sha256: $placement_restored_hash}
      }
    },
    probes: {
      startup: {path: "/actuator/health/readiness", port: "http"},
      readiness: {path: "/actuator/health/readiness", port: "http"},
      liveness: {path: "/actuator/health/liveness", port: "http"}
    },
    observations: {
      baseline: {
        readiness: health("/actuator/health/readiness"; "health/baseline-readiness.json"),
        liveness: health("/actuator/health/liveness"; "health/baseline-liveness.json"),
        serving: serving("health/baseline-serving.json")
      },
      redis_outage: {
        readiness: health("/actuator/health/readiness"; "health/redis-outage-readiness.json"),
        liveness: health("/actuator/health/liveness"; "health/redis-outage-liveness.json"),
        serving: serving("health/redis-outage-serving.json")
      },
      restored: {
        readiness: health("/actuator/health/readiness"; "health/restored-readiness.json"),
        liveness: health("/actuator/health/liveness"; "health/restored-liveness.json"),
        serving: serving("health/restored-serving.json")
      }
    },
    redis_outage: {
      deployment: "redis", replicas_before: 1, replicas_during: 0,
      replicas_after: 1, observed_seconds: 5,
      snapshots: {
        before: {file: "placement/redis-before.json", sha256: $redis_before_hash, replicas: 1},
        during: {file: "placement/redis-during.json", sha256: $redis_during_hash, replicas: 0},
        after: {file: "placement/redis-after.json", sha256: $redis_after_hash, replicas: 1}
      }
    },
    claim_boundary: [
      "source-aligned query-service placement and serving",
      "query-service readiness and liveness remained healthy during a Redis outage",
      "no query-service Pod replacement or restart was observed during the bounded outage",
      "diagnostic-only evidence; not a full-local aggregate certification"
    ]
  }
' >"$report"

java_placement_serving_report_is_passed "$report" ||
  fail 'valid placement/serving report was rejected'

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

help_output="$("$script_dir/run-local-java-placement-serving-check.sh" --help)"
grep -Fq -- '--namespace-run-id' <<<"$help_output" ||
  fail 'runner help omitted namespace ownership input'
grep -Fq -- '--retained-evidence-dir' <<<"$help_output" ||
  fail 'runner help omitted retained provenance input'
grep -Fq 'diagnostic-only' <<<"$help_output" ||
  fail 'runner help omitted diagnostic-only boundary'
grep -Fq 'Redis' <<<"$help_output" ||
  fail 'runner help omitted bounded Redis outage'
grep -Fq 'docker info' "$script_dir/run-local-java-placement-serving-check.sh" ||
  fail 'runner omitted Docker daemon preflight'
grep -Fq 'get clusters' "$script_dir/run-local-java-placement-serving-check.sh" ||
  fail 'runner omitted canonical kind preflight'
grep -Fq 'docker-system-df-before.txt' "$script_dir/run-local-java-placement-serving-check.sh" ||
  fail 'runner omitted pre-observation Docker inventory'
grep -Fq 'docker-system-df-after.txt' "$script_dir/run-local-java-placement-serving-check.sh" ||
  fail 'runner omitted post-observation Docker inventory'

bash -n "$script_dir/lib/local-java-placement-serving.sh" \
  "$script_dir/run-local-java-placement-serving-check.sh"

printf 'Local Java placement/serving contract passed.\n'

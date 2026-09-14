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

expect_reject() {
  local label="$1"
  shift
  if "$@"; then
    fail "$label was accepted"
  fi
}

expect_report_rejects() {
  local label="$1"
  local filter="$2"
  local candidate="$fixture_dir/report-$label.json"

  jq "$filter" "$report" >"$candidate"
  expect_reject "$label" java_placement_serving_report_is_passed "$candidate"
}

expect_runner_rejects() {
  local label="$1"
  local expected_message="$2"
  shift 2
  local output

  if output="$("$script_dir/run-local-java-placement-serving-check.sh" "$@" 2>&1)"; then
    fail "$label was accepted"
  fi
  grep -Fq "$expected_message" <<<"$output" ||
    fail "$label did not report: $expected_message"
}

fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-java-placement-serving.XXXXXX")"
trap 'rm -rf -- "$fixture_dir"' EXIT

deployment="$fixture_dir/deployment.json"
pods="$fixture_dir/pods.json"
nodes="$fixture_dir/nodes.json"
report="$fixture_dir/java-placement-serving.json"

write_deployment_fixture() {
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
              image: "localhost:5001/simplematch/query-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              ports: [{name: "http", containerPort: 8086}],
              startupProbe: {httpGet: {
                path: "/actuator/health/readiness", port: "http"
              }},
              readinessProbe: {httpGet: {
                path: "/actuator/health/readiness", port: "http"
              }},
              livenessProbe: {
                httpGet: {path: "/actuator/health/liveness", port: "http"},
                initialDelaySeconds: 20, periodSeconds: 10, failureThreshold: 3
              }
            }]
          }
        }
      },
      status: {readyReplicas: 2}
    }
  ' >"$deployment"
}

write_workload_fixture() {
  jq -n '
    def pod($name; $uid; $node): {
      metadata: {
        name: $name, uid: $uid,
        labels: {"app.kubernetes.io/name": "query-service",
          "app.kubernetes.io/component": "java-service"}
      },
      spec: {nodeName: $node},
      status: {
        phase: "Running",
        conditions: [{type: "Ready", status: "True"}],
        containerStatuses: [{name: "query-service", restartCount: 0,
          imageID: "docker-pullable://simplematch/query-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
      }
    };
    {items: [
      pod("query-service-a"; "pod-query-a"; "simplematch-live-worker"),
      pod("query-service-b"; "pod-query-b"; "simplematch-live-worker2")
    ]}
  ' >"$pods"

  jq -n '
    {items: [
      {metadata: {name: "simplematch-live-worker",
        labels: {"simplematch.io/node-pool": "local-resilience"}}},
      {metadata: {name: "simplematch-live-worker2",
        labels: {"simplematch.io/node-pool": "local-resilience"}}},
      {metadata: {name: "simplematch-live-control-plane",
        labels: {"simplematch.io/node-pool": "control-plane"}}}
    ]}
  ' >"$nodes"
}

write_redis_fixture() {
  local stage="$1"
  local replicas="$2"

  jq -n --arg stage "$stage" --argjson replicas "$replicas" '
    {
      stage: $stage, deployment_name: "redis", desired_replicas: $replicas,
      ready_replicas: $replicas, ready_pod_count: $replicas,
      pods: (if $replicas == 1 then
        [{name:"redis-0",uid:"redis-pod",node:"simplematch-live-worker",
          phase:"Running",ready:true,restart_count:0,
          image_id:"docker-pullable://redis@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]
        else [] end)
    }
  ' >"$fixture_dir/placement/redis-$stage.json"
}

write_health_body_fixtures() {
  local body_file
  for body_file in \
    baseline-readiness baseline-liveness redis-outage-readiness redis-outage-liveness \
    restored-readiness restored-liveness; do
    printf '%s\n' '{"status":"UP"}' >"$fixture_dir/health/$body_file.json"
  done
  for body_file in baseline-serving redis-outage-serving restored-serving; do
    printf '%s\n' '{"partitions":[]}' >"$fixture_dir/health/$body_file.json"
  done
}

write_health_stage() {
  local stage="$1"
  local readiness_digest liveness_digest serving_digest

  readiness_digest="$(java_placement_serving_sha256_digest \
    "$fixture_dir/health/$stage-readiness.json")"
  liveness_digest="$(java_placement_serving_sha256_digest \
    "$fixture_dir/health/$stage-liveness.json")"
  serving_digest="$(java_placement_serving_sha256_digest \
    "$fixture_dir/health/$stage-serving.json")"
  jq -n \
    --arg stage "$stage" \
    --arg readiness_digest "$readiness_digest" \
    --arg liveness_digest "$liveness_digest" \
    --arg serving_digest "$serving_digest" '
      {
        readiness: {path:"/actuator/health/readiness", http_status:200,
          body_status:"UP",
          body_file:("health/" + $stage + "-readiness.json"),
          body_sha256:$readiness_digest},
        liveness: {path:"/actuator/health/liveness", http_status:200,
          body_status:"UP",
          body_file:("health/" + $stage + "-liveness.json"),
          body_sha256:$liveness_digest},
        serving: {path:"/api/v1/freshness", http_status:200,
          body_status:"SERVING",
          body_file:("health/" + $stage + "-serving.json"),
          body_sha256:$serving_digest}
      }
    ' >"$fixture_dir/health/$stage.json"
}

write_deployment_fixture
write_workload_fixture
mkdir -p "$fixture_dir/placement" "$fixture_dir/health"
java_placement_serving_probe_contract_is_valid "$deployment" ||
  fail 'valid probe contract was rejected'
java_placement_serving_runtime_snapshot_is_ready "$deployment" "$pods" "$nodes" ||
  fail 'valid placement snapshot was rejected'
snapshot_json="$(java_placement_serving_runtime_snapshot "$deployment" "$pods" "$nodes")" ||
  fail 'runtime snapshot could not be normalized'
printf '%s\n' "$snapshot_json" "$snapshot_json" \
  >"$fixture_dir/placement/concatenated.json"
expect_reject 'concatenated placement snapshot' \
  java_placement_serving_snapshot_file_is_ready \
  "$fixture_dir/placement/concatenated.json"

jq '.spec.template.spec.nodeSelector["simplematch.io/node-pool"] = "other-pool"' \
  "$deployment" >"$fixture_dir/deployment-wrong-node-pool.json"
expect_reject 'deployment nodeSelector outside the canonical pool' \
  java_placement_serving_runtime_snapshot_is_ready \
  "$fixture_dir/deployment-wrong-node-pool.json" "$pods" "$nodes"

jq '.spec.template.spec.containers[0].livenessProbe.periodSeconds = 5' \
  "$deployment" >"$fixture_dir/deployment-wrong-liveness-timing.json"
expect_reject 'liveness timing outside the observed contract' \
  java_placement_serving_probe_contract_is_valid \
  "$fixture_dir/deployment-wrong-liveness-timing.json"

jq '.items[1].spec.nodeName = .items[0].spec.nodeName' "$pods" \
  >"$fixture_dir/pods-one-node.json"
expect_reject 'same-node placement' java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-one-node.json" "$nodes"

jq '.items[0].status.containerStatuses[0].imageID = "docker://query-service:latest"' \
  "$pods" >"$fixture_dir/pods-mutable-image.json"
expect_reject 'mutable runtime image identity' \
  java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-mutable-image.json" "$nodes"

jq '.items[0].status.containerStatuses[0].imageID =
  "docker-pullable://simplematch/query-service@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"' \
  "$pods" >"$fixture_dir/pods-wrong-image.json"
expect_reject 'runtime image outside the Deployment identity' \
  java_placement_serving_runtime_snapshot_is_ready \
  "$deployment" "$fixture_dir/pods-wrong-image.json" "$nodes"

for placement_stage in baseline redis-outage restored; do
  printf '%s\n' "$snapshot_json" >"$fixture_dir/placement/$placement_stage.json"
done
write_redis_fixture before 1
write_redis_fixture during 0
write_redis_fixture after 1
cat "$fixture_dir/placement/redis-before.json" \
  "$fixture_dir/placement/redis-before.json" \
  >"$fixture_dir/placement/redis-concatenated.json"
expect_reject 'concatenated Redis snapshot' \
  java_placement_serving_redis_snapshot_is_expected \
  "$fixture_dir/placement/redis-concatenated.json" 1
write_health_body_fixtures
write_health_stage baseline
write_health_stage redis-outage
write_health_stage restored

jq -n \
  --arg namespace "simplematch-java-run" \
  --arg run_id "run-1" \
  --arg source_revision "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  '{namespace:$namespace,namespace_run_id:$run_id,managed_by:"local-resilience",source_revision:$source_revision}' \
  >"$fixture_dir/provenance.json"

java_placement_serving_write_pass_report "$fixture_dir" \
  "$JAVA_PLACEMENT_SERVING_MIN_OBSERVE_SECONDS" ||
  fail 'report assembler did not produce a valid PASS report'
[[ -s "$report" ]] || fail 'report assembler did not publish the report'

cat "$report" "$report" >"$fixture_dir/report-concatenated.json"
expect_reject 'concatenated placement report' \
  java_placement_serving_report_is_passed \
  "$fixture_dir/report-concatenated.json"

ln -s "$fixture_dir" "$fixture_dir/report-alias"
expect_reject 'report through a symlinked parent path' \
  java_placement_serving_report_is_passed \
  "$fixture_dir/report-alias/java-placement-serving.json"
rm "$fixture_dir/report-alias"

cp "$fixture_dir/placement/baseline.json" "$fixture_dir/baseline-placement.original"
printf '%s\n' '{}' >"$fixture_dir/placement/baseline.json"
expect_reject 'tampered linked placement evidence' \
  java_placement_serving_report_is_passed "$report"
mv "$fixture_dir/baseline-placement.original" "$fixture_dir/placement/baseline.json"

cp "$fixture_dir/health/baseline-readiness.json" "$fixture_dir/baseline-readiness.original"
printf '%s\n' '{"status":"DOWN"}' >"$fixture_dir/health/baseline-readiness.json"
invalid_readiness_digest="$(java_placement_serving_sha256_digest \
  "$fixture_dir/health/baseline-readiness.json")"
jq --arg digest "$invalid_readiness_digest" \
  '.observations.baseline.readiness.body_sha256 = $digest' "$report" \
  >"$fixture_dir/report-invalid-readiness.json"
expect_reject 'invalid linked readiness body after digest update' \
  java_placement_serving_report_is_passed \
  "$fixture_dir/report-invalid-readiness.json"
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
expect_reject 'cross-stage Pod identity mismatch' \
  java_placement_serving_report_is_passed \
  "$fixture_dir/report-identity-mismatch.json"
mv "$fixture_dir/redis-outage.original" "$fixture_dir/placement/redis-outage.json"

cp "$fixture_dir/placement/redis-outage.json" "$fixture_dir/redis-outage.original"
different_image='query-service@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'
jq --arg image "$different_image" '
  .deployment_image = $image | (.pods[].image_id) = $image
' "$fixture_dir/placement/redis-outage.json" \
  >"$fixture_dir/placement/redis-outage.changed-image.json"
mv "$fixture_dir/placement/redis-outage.changed-image.json" \
  "$fixture_dir/placement/redis-outage.json"
changed_image_digest="$(java_placement_serving_sha256_digest \
  "$fixture_dir/placement/redis-outage.json")"
jq --arg digest "$changed_image_digest" \
  '.placement.snapshots.outage.sha256 = $digest' "$report" \
  >"$fixture_dir/report-image-mismatch.json"
expect_reject 'cross-stage image mismatch' \
  java_placement_serving_report_is_passed \
  "$fixture_dir/report-image-mismatch.json"
mv "$fixture_dir/redis-outage.original" "$fixture_dir/placement/redis-outage.json"

expect_report_rejects 'report-liveness-failed' \
  '.observations.redis_outage.liveness.http_status = 503'
expect_report_rejects 'report-serving-metadata-mismatch' \
  '.observations.baseline.serving.body_status = "DOWN"'
expect_report_rejects 'report-redis-snapshot-mismatch' \
  '.redis_outage.snapshots.during.file = "placement/redis-before.json"'
expect_report_rejects 'report-swapped-placement-stages' \
  '.placement.snapshots.baseline.file = "placement/restored.json" |
   .placement.snapshots.restored.file = "placement/baseline.json"'
expect_report_rejects 'report-no-outage' \
  '.redis_outage.replicas_during = 1'
expect_report_rejects 'report-mismatched-target' \
  '.target.pod = "query-service-b"'
expect_report_rejects 'report-overbroad-claim' \
  '.claim_boundary = ["full-local certification passed"]'

help_output="$("$script_dir"/run-local-java-placement-serving-check.sh --help)"
grep -Fq -- '--namespace-run-id' <<<"$help_output" ||
  fail 'runner help omitted namespace ownership input'
grep -Fq -- '--retained-evidence-dir' <<<"$help_output" ||
  fail 'runner help omitted retained provenance input'
grep -Fq 'diagnostic-only' <<<"$help_output" ||
  fail 'runner help omitted diagnostic-only boundary'

symlink_parent="$fixture_dir/evidence-alias"
ln -s "$fixture_dir" "$symlink_parent"
expect_runner_rejects 'symlinked evidence path' 'path contains a symlink component' \
  --namespace simplematch-java-run \
  --namespace-run-id run-1 \
  --retained-evidence-dir "$fixture_dir" \
  --evidence-dir "$symlink_parent/new-run"
[[ ! -e "$fixture_dir/new-run" ]] ||
  fail 'runner created evidence outside the owned path after symlink rejection'

retained_gate_fixture="$fixture_dir/retained-gate"
mkdir -p "$retained_gate_fixture"
printf '%s\n' '- status: PASSED' >"$retained_gate_fixture/report.md"
jq -n '{schemaVersion:1,phases:[{phaseId:"source-preflight",decision:"EXECUTE"}]}' \
  >"$retained_gate_fixture/plan.json"
expect_runner_rejects 'incomplete retained PASS' 'evidence manifest is missing' \
  --namespace simplematch-java-run \
  --namespace-run-id run-1 \
  --retained-evidence-dir "$retained_gate_fixture" \
  --evidence-dir "$fixture_dir/gate-run"

for script in \
  "$script_dir/lib/local-java-placement-serving.sh" \
  "$script_dir/run-local-java-placement-serving-check.sh" \
  "$script_dir/test-local-java-placement-serving.sh"; do
  bash -n "$script"
done

printf 'Local Java placement/serving contract passed.\n'

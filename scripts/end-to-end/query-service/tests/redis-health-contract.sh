#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
# shellcheck source=scripts/end-to-end/query-service/lib/redis-health.sh
source "$script_dir/../lib/redis-health.sh"

fixture_dir="$(mktemp -d)"
trap 'rm -rf -- "$fixture_dir"' EXIT
evidence_dir="$fixture_dir"
mkdir -p "$evidence_dir/diagnostics"
image_digest="$(printf 'a%.0s' {1..64})"
mock_health_body=UP
mock_health_http=200
retained_evidence_dir="$fixture_dir/retained"
simplematch_local_image_lock_digest_reference() {
  [[ "$2" == query-service ]] || return 1
  printf 'registry/query-service@sha256:%s\n' "$image_digest"
}

jq -n --arg digest "$image_digest" '{
  metadata:{name:"query-service"},
  spec:{replicas:2,template:{spec:{
    nodeSelector:{"simplematch.io/node-pool":"local-resilience"},
    containers:[{
      name:"query-service",image:("registry/query-service@sha256:"+$digest),
      readinessProbe:{httpGet:{path:"/actuator/health/readiness",port:"http"}},
      livenessProbe:{httpGet:{path:"/actuator/health/liveness",port:"http"},
        initialDelaySeconds:20,periodSeconds:10,failureThreshold:3}
    }]
  }}}
}' >"$fixture_dir/deployment.json"

jq -n --arg digest "$image_digest" '{
  items:[range(0;2) | {
    metadata:{name:("query-service-"+(tostring)),uid:("uid-"+(tostring))},
    spec:{nodeName:("worker-"+(tostring))},
    status:{phase:"Running",conditions:[{type:"Ready",status:"True"}],
      containerStatuses:[{
        name:"query-service",ready:true,restartCount:0,
        imageID:("containerd://image@sha256:"+$digest)
      }]}
  }]
}' >"$fixture_dir/pods.json"
printf '{"spec":{"replicas":0},"status":{"readyReplicas":0}}\n' >"$fixture_dir/redis.json"
printf '{"items":[]}\n' >"$fixture_dir/redis-pods.json"

kns() {
  case "$*" in
    'get deployment query-service -o json') cat "$fixture_dir/deployment.json" ;;
    'get deployment redis -o json') cat "$fixture_dir/redis.json" ;;
    'get pods -l app.kubernetes.io/name=query-service,app.kubernetes.io/component=java-service -o json')
      cat "$fixture_dir/pods.json" ;;
    'get pods -l app.kubernetes.io/name=redis -o json') cat "$fixture_dir/redis-pods.json" ;;
    *) return 1 ;;
  esac
}

stop_query_port_forward() { :; }
start_port_forward() {
  printf -v "$5" 8091
  printf '%s\n' "$1" >>"$fixture_dir/observed-pods"
}
start_query_port_forward() { :; }
curl() {
  while (($#)); do
    if [[ "$1" == -o ]]; then
      printf '{"status":"%s"}\n' "$mock_health_body" >"$2"
      shift 2
    else
      shift
    fi
  done
  printf '%s' "$mock_health_http"
}
fetch_query_json() {
  [[ "$1" == /api/v1/freshness ]] || return 1
  printf '{"partitions":[]}\n' >"$2"
}
fail() { printf 'R21 Redis health contract failed: %s\n' "$*" >&2; exit 1; }

query_redis_health_capture baseline || fail 'two healthy Pod identities were rejected'
[[ "$query_redis_health_observe_seconds" == 60 ]] || fail 'wrong configured liveness window'
[[ "$(wc -l <"$fixture_dir/observed-pods")" == 2 ]] || fail 'both Pods must be probed directly'
query_redis_health_assert_outage || fail 'observed zero-Redis state was rejected'
query_redis_health_capture redis-outage || fail 'stable Redis-outage Pods were rejected'
query_redis_health_capture restored || fail 'stable restoration Pods were rejected'

jq '.items[0].metadata.uid="changed-uid"' \
  "$fixture_dir/pods.json" >"$fixture_dir/pods.new"
mv "$fixture_dir/pods.new" "$fixture_dir/pods.json"
if query_redis_health_capture redis-outage; then fail 'changed Pod UID was accepted'; fi
jq '.items[0].metadata.uid="uid-0"' \
  "$fixture_dir/pods.json" >"$fixture_dir/pods.new"
mv "$fixture_dir/pods.new" "$fixture_dir/pods.json"

jq '.items[0].metadata.name="../unsafe-path"' \
  "$fixture_dir/pods.json" >"$fixture_dir/pods.new"
mv "$fixture_dir/pods.new" "$fixture_dir/pods.json"
if query_redis_health_capture redis-outage; then fail 'unsafe Pod name was accepted'; fi
jq '.items[0].metadata.name="query-service-0"' \
  "$fixture_dir/pods.json" >"$fixture_dir/pods.new"
mv "$fixture_dir/pods.new" "$fixture_dir/pods.json"

jq '.items[1].status.containerStatuses[0].restartCount=1' \
  "$fixture_dir/pods.json" >"$fixture_dir/pods.new"
mv "$fixture_dir/pods.new" "$fixture_dir/pods.json"
if query_redis_health_capture redis-outage; then fail 'a restarted Pod was accepted'; fi
jq '.items[1].status.containerStatuses[0].restartCount=0' \
  "$fixture_dir/pods.json" >"$fixture_dir/pods.new"
mv "$fixture_dir/pods.new" "$fixture_dir/pods.json"

jq '.items[0].status.conditions[0].status="False"' \
  "$fixture_dir/pods.json" >"$fixture_dir/pods.new"
mv "$fixture_dir/pods.new" "$fixture_dir/pods.json"
if query_redis_health_capture redis-outage; then fail 'a NotReady Pod was accepted'; fi
jq '.items[0].status.conditions[0].status="True"' \
  "$fixture_dir/pods.json" >"$fixture_dir/pods.new"
mv "$fixture_dir/pods.new" "$fixture_dir/pods.json"

mock_health_http=503
if query_redis_health_probe baseline query-service-0; then fail 'HTTP 503 health was accepted'; fi
mock_health_http=200
mock_health_body=DOWN
if query_redis_health_probe baseline query-service-0; then fail 'DOWN actuator body was accepted'; fi
mock_health_body=UP

jq '.spec.replicas=1' "$fixture_dir/redis.json" >"$fixture_dir/redis.new"
mv "$fixture_dir/redis.new" "$fixture_dir/redis.json"
if query_redis_health_assert_outage; then fail 'Redis was not actually scaled down'; fi

printf '%s\n' 'R21 two-Pod identity, liveness-window, Redis-outage and authenticated-probe seams are valid.'

#!/usr/bin/env bash

# R21: observe both actual Query Pods during the existing Redis outage.
# The Query certification runner owns the outage, namespace, credentials and cleanup.

query_redis_health_probe() {
  local stage="$1" pod="$2" health status
  start_port_forward "pod/$pod" 8086 \
    "$evidence_dir/diagnostics/query-$stage-$pod-forward.log" \
    query_port_forward_pid query_port || return 1
  for health in readiness liveness; do
    status="$(curl --noproxy '*' --connect-timeout 2 --max-time 20 -sS \
      -o "$evidence_dir/diagnostics/query-$stage-$pod-$health.json" -w '%{http_code}' \
      "http://127.0.0.1:$query_port/actuator/health/$health")" || return 1
    [[ "$status" == 200 ]] || return 1
    jq -e '.status == "UP"' \
      "$evidence_dir/diagnostics/query-$stage-$pod-$health.json" >/dev/null || return 1
  done
  # query_http.py supplies the approved operations-read Bearer token and validates the body.
  fetch_query_json /api/v1/freshness \
    "$evidence_dir/diagnostics/query-$stage-$pod-serving.json" || return 1
  stop_query_port_forward
}

query_redis_health_capture() {
  local stage="$1" deployment pods snapshot
  local pod expected_image

  deployment="$evidence_dir/diagnostics/query-$stage-deployment.json"
  pods="$evidence_dir/diagnostics/query-$stage-pods.json"
  snapshot="$evidence_dir/diagnostics/query-$stage-health-owners.json"

  kns get deployment query-service -o json >"$deployment" || return 1
  kns get pods -l 'app.kubernetes.io/name=query-service,app.kubernetes.io/component=java-service' \
    -o json >"$pods" || return 1
  jq -ne --slurpfile deployment "$deployment" --slurpfile pods "$pods" '
    ($deployment[0]) as $d | ($pods[0].items) as $items |
    ($d.spec.template.spec.containers | map(select(.name == "query-service"))) as $containers |
    select($d.metadata.name == "query-service" and $d.spec.replicas == 2
      and $d.spec.template.spec.nodeSelector["simplematch.io/node-pool"] == "local-resilience"
      and ($containers | length) == 1 and ($items | length) == 2) |
    ($containers[0]) as $c |
    select(($c.image | test("@sha256:[0-9a-f]{64}$"))
      and $c.readinessProbe.httpGet.path == "/actuator/health/readiness"
      and $c.livenessProbe.httpGet.path == "/actuator/health/liveness"
      and $c.readinessProbe.httpGet.port == "http"
      and $c.livenessProbe.httpGet.port == "http"
      and ($c.livenessProbe.initialDelaySeconds | type) == "number"
      and ($c.livenessProbe.periodSeconds | type) == "number"
      and ($c.livenessProbe.failureThreshold | type) == "number"
      and $c.livenessProbe.periodSeconds > 0
      and $c.livenessProbe.failureThreshold > 0) |
    [ $items[] | (.status.containerStatuses | map(select(.name == "query-service"))) as $statuses
      | select(.metadata.deletionTimestamp == null and .status.phase == "Running"
          and any(.status.conditions[]?; .type == "Ready" and .status == "True")
          and ($statuses | length) == 1
          and ($statuses[0].ready == true)
          and ($statuses[0].restartCount | type) == "number"
          and ($statuses[0].imageID | endswith($c.image | split("@")[-1])))
      | {name:.metadata.name,uid:.metadata.uid,node:.spec.nodeName,
         restarts:$statuses[0].restartCount,imageId:$statuses[0].imageID}
    ] | sort_by(.name) as $owners |
    select(($owners | length) == 2
      and ([$owners[].name] | unique | length) == 2
      and ([$owners[].uid] | unique | length) == 2
      and ([$owners[].node] | unique | length) == 2
      and all($owners[]; (.name | type == "string" and test("^query-service-[a-z0-9-]+$"))
        and (.uid | type == "string" and length > 0)
        and (.node | type == "string" and length > 0))) |
    {image:$c.image,
     livenessWindowSeconds:($c.livenessProbe.initialDelaySeconds
       + $c.livenessProbe.periodSeconds * ($c.livenessProbe.failureThreshold + 1)),
     pods:$owners}
  ' >"$snapshot" || return 1

  if [[ "$stage" == baseline ]]; then
    expected_image="$(simplematch_local_image_lock_digest_reference \
      "$retained_evidence_dir/local-images.lock" query-service)" || return 1
    jq -e --arg expected "$expected_image" '.image == $expected' "$snapshot" >/dev/null || return 1
    query_redis_health_observe_seconds="$(jq -er '.livenessWindowSeconds | select(. >= 60 and . <= 120)' "$snapshot")" || return 1
  else
    jq -e -s '.[0] == .[1]' \
      "$evidence_dir/diagnostics/query-baseline-health-owners.json" "$snapshot" >/dev/null || return 1
  fi

  # Route to each concrete Pod, not the Service: a healthy replica cannot hide a failed one.
  stop_query_port_forward || return 1
  while IFS= read -r pod; do
    query_redis_health_probe "$stage" "$pod" || return 1
  done < <(jq -r '.pods[].name' "$snapshot")
  start_query_port_forward || return 1
}

query_redis_health_assert_outage() {
  local deployment="$evidence_dir/diagnostics/redis-outage-deployment.json"
  local pods="$evidence_dir/diagnostics/redis-outage-pods.json"
  kns get deployment redis -o json >"$deployment" || return 1
  kns get pods -l 'app.kubernetes.io/name=redis' -o json >"$pods" || return 1
  jq -e '.spec.replicas == 0 and (.status.readyReplicas // 0) == 0' "$deployment" >/dev/null || return 1
  jq -e 'all(.items[]?; all(.status.conditions[]?;
    .type != "Ready" or .status != "True"))' "$pods" >/dev/null
}

#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$repo_root/scripts/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$repo_root/scripts/lib/local-kind.sh"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$repo_root/scripts/lib/local-certification-provenance.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/cluster-data.sh
source "$repo_root/scripts/end-to-end/critical-consumers/lib/cluster-data.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/test-interfaces.sh
source "$repo_root/scripts/end-to-end/critical-consumers/lib/test-interfaces.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="kind-$cluster_name"
namespace=""
evidence_dir=""
timeout_seconds="${SIMPLEMATCH_MARKET_DATA_CERTIFICATION_TIMEOUT_SECONDS:-180}"
current_stage="preflight"
failure_reason=""
evidence_initialized=false
restoration_failed=false
certification_succeeded=false
projection_port_forward_pid=""
projection_port=""
streamer_port_forward_pid=""
streamer_port=""
observer_pid=""
original_projection_replicas=""
original_redis_replicas=""
original_streamer_replicas=""
retained_evidence_dir=""
retained_run_id=""
source_revision=""
projection_environment_modified=false
redis_scaled=false
projection_scaled=false
operator_token=""
venue_mic=""
symbol=""

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/end-to-end/market-data/run-certification.sh \
    --namespace NAME \
    --evidence-dir PATH \
    [--timeout-seconds N]

Certifies the deployed runtime market-data projection through retained Kafka,
the authenticated replay interface, Redis repair, and the public gRPC stream.
The namespace must belong to the source-aligned, lifecycle-labelled retained
production-like run. Set SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR if needed.
EOF_USAGE
}

die() {
  failure_reason="$*"
  printf 'market-data certification: %s\n' "$failure_reason" >&2
  exit 1
}

on_error() {
  local status="$1"
  [[ -n "$failure_reason" ]] ||
    failure_reason="unexpected command failure during $current_stage"
  return "$status"
}
trap 'on_error $?' ERR

stop_observer() {
  stop_background_process "${observer_pid:-}"
  observer_pid=""
}

stop_projection_port_forward() {
  stop_background_process "${projection_port_forward_pid:-}"
  projection_port_forward_pid=""
  projection_port=""
}

stop_streamer_port_forward() {
  stop_background_process "${streamer_port_forward_pid:-}"
  streamer_port_forward_pid=""
  streamer_port=""
}

restore_environment() {
  set +e
  stop_observer
  stop_projection_port_forward
  stop_streamer_port_forward

  if [[ "$redis_scaled" == true && -n "$original_redis_replicas" ]]; then
    if scale_deployment redis "$original_redis_replicas"; then
      redis_scaled=false
    else
      restoration_failed=true
    fi
  fi
  if [[ "$projection_environment_modified" == true ]]; then
    if kns set env deployment/market-data-projection \
      SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_HTTP_ENABLED- \
      SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_OPERATOR_TOKEN- >/dev/null 2>&1; then
      projection_environment_modified=false
    else
      restoration_failed=true
    fi
  fi
  if [[ "$projection_scaled" == true && -n "$original_projection_replicas" ]]; then
    if scale_deployment market-data-projection "$original_projection_replicas"; then
      projection_scaled=false
    else
      restoration_failed=true
    fi
  elif [[ -n "$original_projection_replicas" ]]; then
    kns rollout status deployment/market-data-projection \
      --timeout="${timeout_seconds}s" >/dev/null 2>&1 || restoration_failed=true
  fi
  set -e
}

write_failure_verdict() {
  local status="$1"
  [[ "$evidence_initialized" == true ]] || return 0
  [[ -f "$evidence_dir/verdict.json" ]] && return 0
  jq -n \
    --arg namespace "$namespace" \
    --arg stage "$current_stage" \
    --arg reason "${failure_reason:-unexpected command failure}" \
    --argjson exitStatus "$status" \
    --argjson restorationFailed "$([[ "$restoration_failed" == true ]] && echo true || echo false)" \
    '{status:"FAIL",namespace:$namespace,stage:$stage,reason:$reason,
      exitStatus:$exitStatus,restorationFailed:$restorationFailed}' \
    >"$evidence_dir/verdict.json"
}

cleanup() {
  local status="$?"
  trap - ERR EXIT INT TERM
  if [[ "$certification_succeeded" != true ]]; then
    restore_environment
  fi
  if [[ "$restoration_failed" == true ]]; then
    status=1
    [[ -n "$failure_reason" ]] || failure_reason="environment restoration failed"
  fi
  if [[ "$evidence_initialized" == true && "$certification_succeeded" != true ]] &&
      (( status == 0 )); then
    status=1
    [[ -n "$failure_reason" ]] || failure_reason="certification ended without a passed verdict"
  fi
  if (( status != 0 )) || [[ "$certification_succeeded" != true ]]; then
    write_failure_verdict "$status"
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'failure_reason="certification interrupted during $current_stage"; exit 130' INT TERM

postgres_json() {
  local sql="$1"
  local destination="$2"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1
  kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -c "$sql" >"$destination"
  jq -e . "$destination" >/dev/null
}

capture_projection_instrument() {
  local destination="$1"
  postgres_json "
    SELECT json_build_object(
      'venueMic', TRIM(venue_mic),
      'symbol', symbol,
      'instrumentSequence', instrument_sequence,
      'sourcePartitionId', source_partition_id,
      'sourceKafkaOffset', source_offset_value,
      'redisPending', redis_snapshot_pending
    )
    FROM market_data_projection.instrument_market_data
    ORDER BY updated_at_unix_ms DESC, venue_mic, symbol
    LIMIT 1;
  " "$destination"
}

capture_projection_state() {
  local destination="$1"
  postgres_json "
    SELECT json_build_object(
      'inboxCount', (SELECT COUNT(*) FROM market_data_projection.matching_event_inbox),
      'instrumentCount', (SELECT COUNT(*) FROM market_data_projection.instrument_market_data),
      'outboxCount', (SELECT COUNT(*) FROM market_data_projection.market_data_events_outbox),
      'pendingRedisCount', (
        SELECT COUNT(*) FROM market_data_projection.instrument_market_data
        WHERE redis_snapshot_pending = TRUE
      ),
      'deadLetterCount', (
        SELECT COUNT(*) FROM market_data_projection.matching_event_dead_letters
      )
    );
  " "$destination"
}

capture_market_data_topic_profile() {
  local broker description configuration
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || return 1
  description="$evidence_dir/marketdata-events-topic.txt"
  configuration="$evidence_dir/marketdata-events-config.txt"
  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:9092 --describe --topic marketdata.events \
    >"$description" 2>"$evidence_dir/marketdata-events-topic.stderr" || return 1
  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-configs.sh \
    --bootstrap-server kafka:9092 --entity-type topics --entity-name marketdata.events \
    --describe --all >"$configuration" \
    2>"$evidence_dir/marketdata-events-config.stderr" || return 1
  grep -Fq "PartitionCount: 15" "$description" || return 1
  grep -Fq "ReplicationFactor: 3" "$description" || return 1
  grep -Fq "cleanup.policy=delete" "$configuration" || return 1
  grep -Fq "retention.ms=2592000000" "$configuration" || return 1
  grep -Fq "min.insync.replicas=2" "$configuration" || return 1
}

start_projection_port_forward() {
  start_port_forward service/market-data-projection 8080 \
    "$evidence_dir/diagnostics/projection-port-forward.log" \
    projection_port_forward_pid projection_port ||
    die "projection management port-forward did not become ready"
}

start_market_data_port_forwards() {
  start_projection_port_forward
  start_port_forward service/marketdata-streamer 50053 \
    "$evidence_dir/diagnostics/streamer-port-forward.log" \
    streamer_port_forward_pid streamer_port ||
    die "market-data streamer port-forward did not become ready"
}

start_snapshot_observer() {
  local phase="$1"
  local snapshot="$evidence_dir/${phase}-snapshot.json"
  local ready="$evidence_dir/${phase}-observer-ready"
  local log="$evidence_dir/diagnostics/${phase}-observer.log"
  rm -f -- "$snapshot" "$ready"
  SIMPLEMATCH_MARKET_DATA_HOST=127.0.0.1 \
    SIMPLEMATCH_MARKET_DATA_PORT="$streamer_port" \
    SIMPLEMATCH_MARKET_DATA_VENUE_MIC="$venue_mic" \
    SIMPLEMATCH_MARKET_DATA_SYMBOL="$symbol" \
    SIMPLEMATCH_MARKET_DATA_TIMEOUT_SECONDS="$timeout_seconds" \
    SIMPLEMATCH_MARKET_DATA_EVIDENCE="$snapshot" \
    SIMPLEMATCH_MARKET_DATA_READY_FILE="$ready" \
    GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/simplematch-gradle-cache}" \
    "$repo_root/gradlew" --no-daemon -q -Dkotlin.compiler.execution.strategy=in-process \
      :tools:risk-matching-e2e-verifier:observeMarketDataSnapshot >"$log" 2>&1 &
  observer_pid="$!"

  for _ in $(seq 1 "$timeout_seconds"); do
    [[ -s "$ready" ]] && return 0
    if ! kill -0 "$observer_pid" >/dev/null 2>&1; then
      cat "$log" >&2
      return 1
    fi
    sleep 1
  done
  return 1
}

wait_snapshot_observer() {
  local phase="$1"
  local status=0
  wait "$observer_pid" || status="$?"
  observer_pid=""
  if (( status != 0 )); then
    cat "$evidence_dir/diagnostics/${phase}-observer.log" >&2
    return "$status"
  fi
  jq -e \
    --arg venue "$venue_mic" \
    --arg symbol "$symbol" \
    '.completeSnapshot == true and .venueMic == $venue and .symbol == $symbol' \
    "$evidence_dir/${phase}-snapshot.json" >/dev/null
}

reset_projection_state() {
  local phase="$1"
  local response="$evidence_dir/${phase}-reset-response.json"
  local status
  status="$(curl --connect-timeout 2 --max-time 30 -sS -o "$response" -w '%{http_code}' \
    -X POST \
    -H "X-SimpleMatch-Projection-Token: $operator_token" \
    "http://127.0.0.1:${projection_port}/internal/market-data/rebuild")" || return 1
  [[ "$status" == 200 ]] || return 1
  jq -e '.status == "RESET_COMPLETE"' "$response" >/dev/null
}

reset_projection_offsets() {
  local phase="$1"
  local broker
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || return 1
  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka:9092 \
    --group market-data-projection \
    --topic matching.events \
    --reset-offsets --to-earliest --execute \
    >"$evidence_dir/${phase}-offset-reset.txt" 2>"$evidence_dir/${phase}-offset-reset.stderr"
}

restart_projection() {
  kns rollout restart deployment/market-data-projection >/dev/null
}

wait_for_redis_repair() {
  local key="marketdata:snapshot:${venue_mic}:${symbol}"
  for _ in $(seq 1 "$timeout_seconds"); do
    local pending value
    pending="$(
      kns exec "$(postgres_pod)" -c postgres -- psql -U simplematch -d simplematch -At \
        -v ON_ERROR_STOP=1 -c "
          SELECT redis_snapshot_pending
          FROM market_data_projection.instrument_market_data
          WHERE venue_mic = '$venue_mic' AND symbol = '$symbol';
        " 2>/dev/null || true
    )"
    value="$(kns exec deployment/redis -- redis-cli EXISTS "$key" 2>/dev/null || true)"
    if [[ "$pending" == f && "$value" == 1 ]]; then
      jq -n --arg key "$key" '{key:$key,present:true,pending:false}' \
        >"$evidence_dir/redis-repair.json"
      return 0
    fi
    sleep 1
  done
  return 1
}

while (($# > 0)); do
  case "$1" in
    --namespace)
      namespace="${2:?--namespace requires a value}"
      shift 2
      ;;
    --evidence-dir)
      evidence_dir="${2:?--evidence-dir requires a value}"
      shift 2
      ;;
    --timeout-seconds)
      timeout_seconds="${2:?--timeout-seconds requires a value}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown option: $1"
      ;;
  esac
done

[[ -n "$namespace" ]] || die "--namespace is required"
[[ -n "$evidence_dir" ]] || die "--evidence-dir is required"
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die "--timeout-seconds must be positive"
(( timeout_seconds <= 300 )) || die "--timeout-seconds must not exceed 300"
for tool in kubectl docker jq curl awk sed grep date seq sleep tr cp mv git timeout; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
timeout --foreground 120 "$repo_root/gradlew" -q -p "$repo_root" \
  :tools:recovery-evidence-verifier:installDist || die "cannot prepare Market Data evidence verifier"
evidence_verifier="$repo_root/tools/recovery-evidence-verifier/build/install/recovery-evidence-verifier/bin/recovery-evidence-verifier"
[[ -x "$evidence_verifier" ]] || die "Market Data evidence verifier is unavailable"
[[ "$(kubectl config current-context)" == "$context" ]] ||
  die "current Kubernetes context must be $context"
kubectl get namespace "$namespace" >/dev/null 2>&1 || die "namespace does not exist"
simplematch_kind_namespace_is_disposable \
  "$context" "$namespace" local-production-like-certification ||
  die "refusing certification outside the run-owned production-like namespace"

mkdir -p "$evidence_dir"
evidence_dir="$(cd -- "$evidence_dir" && pwd)"
shopt -s nullglob dotglob
existing_evidence=("$evidence_dir"/*)
shopt -u nullglob dotglob
((${#existing_evidence[@]} == 0)) || die "evidence directory must be empty"
mkdir -p "$evidence_dir/diagnostics"
evidence_initialized=true

current_stage="verify retained runtime source and image identity"
retained_evidence_dir="$(simplematch_production_like_evidence_dir "$repo_root")"
simplematch_certification_verifier_image \
  "$repo_root" "$namespace" "$retained_evidence_dir" >/dev/null ||
  die "retained production-like source or verifier-image provenance is not valid"
retained_run_id="$(awk -F= '$1 == "run_id" {print substr($0, index($0, "=") + 1)}' \
  "$retained_evidence_dir/run-context")"
namespace_run_id="$(kubectl --context "$context" get namespace "$namespace" \
  -o jsonpath='{.metadata.labels.simplematch\.io/run-id}')"
[[ -n "$retained_run_id" && "$namespace_run_id" == "$retained_run_id" ]] ||
  die "namespace run-id does not match retained production-like evidence"
source_revision="$(simplematch_certification_source_revision "$repo_root")" ||
  die "current runtime source has uncommitted or untracked changes"
expected_projection_image="$(simplematch_local_image_lock_digest_reference \
  "$retained_evidence_dir/local-images.lock" market-data-projection)" ||
  die "retained Market Data projection image lock is unavailable"
expected_streamer_image="$(simplematch_local_image_lock_digest_reference \
  "$retained_evidence_dir/local-images.lock" marketdata-streamer)" ||
  die "retained public-streamer image lock is unavailable"
kns get deployment market-data-projection marketdata-streamer redis -o json \
  >"$evidence_dir/deployments-before.json" ||
  die "deployed Market Data and Redis objects are unavailable"
jq -e --arg projection "$expected_projection_image" --arg streamer "$expected_streamer_image" '
  (.items | length) == 3
  and ([.items[] | select(.metadata.name == "market-data-projection")
    | .spec.template.spec.containers[]? | select(.name == "market-data-projection")
    | .image] == [$projection])
  and ([.items[] | select(.metadata.name == "marketdata-streamer")
    | .spec.template.spec.containers[]? | select(.name == "marketdata-streamer")
    | .image] == [$streamer])
' "$evidence_dir/deployments-before.json" >/dev/null ||
  die "Market Data deployed images do not match the retained immutable source lock"

current_stage="capture baseline and provenance"
original_projection_replicas="$(workload_replicas deployment market-data-projection)"
original_redis_replicas="$(workload_replicas deployment redis)"
original_streamer_replicas="$(workload_replicas deployment marketdata-streamer)"
(( original_projection_replicas > 0 )) || die "market-data projection must be running"
(( original_redis_replicas == 1 )) || die "expected one Redis replica"
(( original_streamer_replicas == 1 )) || die "expected one market-data streamer replica"
capture_consumer_state "$evidence_dir/critical-before.json" ||
  die "cannot capture critical consumer baseline"
capture_market_data_topic_profile ||
  die "marketdata.events does not satisfy the production-shaped topic profile"
capture_projection_instrument "$evidence_dir/selected-instrument.json" ||
  die "projection has no retained instrument to replay"
venue_mic="$(jq -er '.venueMic | select(length == 4)' "$evidence_dir/selected-instrument.json")"
symbol="$(jq -er '.symbol | select(length > 0)' "$evidence_dir/selected-instrument.json")"
kns get pods \
  -l 'app.kubernetes.io/name in (market-data-projection,marketdata-streamer,redis)' \
  -o json >"$evidence_dir/runtime-images-before.json" ||
  die "Market Data runtime Pods cannot be inspected"
jq -e --arg projection "$expected_projection_image" --arg streamer "$expected_streamer_image" '
  [.items[] | select(.metadata.labels["app.kubernetes.io/name"] == "market-data-projection")] as $p |
  [.items[] | select(.metadata.labels["app.kubernetes.io/name"] == "marketdata-streamer")] as $s |
  ($p | length) > 0 and ($s | length) == 1
  and all(($p + $s)[]; .metadata.deletionTimestamp == null
    and .status.phase == "Running"
    and any(.status.conditions[]?; .type == "Ready" and .status == "True"))
  and all($p[]; ([.spec.containers[]?
    | select(.name == "market-data-projection" and .image == $projection)] | length) == 1)
  and all($s[]; ([.spec.containers[]?
    | select(.name == "marketdata-streamer" and .image == $streamer)] | length) == 1)
' "$evidence_dir/runtime-images-before.json" >/dev/null ||
  die "Market Data running Pods do not match the retained locked images"
jq -n \
  --arg sourceRevision "$source_revision" \
  --arg runId "$retained_run_id" \
  --arg projectionImage "$expected_projection_image" \
  --arg streamerImage "$expected_streamer_image" \
  --arg namespace "$namespace" \
  --arg context "$context" \
  --arg venueMic "$venue_mic" \
  --arg symbol "$symbol" \
  '{sourceRevision:$sourceRevision,runId:$runId,namespace:$namespace,context:$context,
    images:{projection:$projectionImage,streamer:$streamerImage},
    instrument:{venueMic:$venueMic,symbol:$symbol}}' >"$evidence_dir/provenance.json"

current_stage="enable the authenticated replay interface"
existing_override_count="$(
  kns get deployment market-data-projection -o json |
    jq '[.spec.template.spec.containers[] | select(.name == "market-data-projection")
      | .env[]? | select(.name == "SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_HTTP_ENABLED"
        or .name == "SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_OPERATOR_TOKEN")] | length'
)"
(( existing_override_count == 0 )) || die "projection already defines replay environment overrides"
operator_token="$(tr -d '-' </proc/sys/kernel/random/uuid)"
projection_scaled=true
scale_deployment market-data-projection 1
projection_environment_modified=true
kns set env deployment/market-data-projection \
  SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_HTTP_ENABLED=true \
  SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_OPERATOR_TOKEN="$operator_token" >/dev/null
wait_deployment_replicas market-data-projection 1 || die "projection replay adapter is not ready"
start_market_data_port_forwards

current_stage="capture deterministic replay baseline"
start_snapshot_observer baseline || die "baseline gRPC observer did not become ready"
reset_projection_state baseline || die "baseline projection reset failed"
reset_projection_offsets baseline || die "baseline projection offset reset failed"
restart_projection
wait_snapshot_observer baseline || die "baseline snapshot was not observed"
wait_deployment_replicas market-data-projection 1 || die "baseline projection replay did not become ready"
capture_projection_state "$evidence_dir/baseline-projection-state.json" ||
  die "cannot capture baseline projection state"
stop_projection_port_forward
start_projection_port_forward

current_stage="replay while Redis is unavailable"
reset_projection_state rebuilt || die "outage projection reset failed"
redis_scaled=true
scale_deployment redis 0
start_snapshot_observer rebuilt || die "rebuild gRPC observer did not become ready"
reset_projection_offsets rebuilt || die "rebuild projection offset reset failed"
restart_projection
wait_snapshot_observer rebuilt || die "rebuilt snapshot was not observed"
capture_projection_state "$evidence_dir/outage-projection-state.json" ||
  die "cannot capture durable projection state during Redis outage"
jq -e '.instrumentCount > 0 and .pendingRedisCount > 0' \
  "$evidence_dir/outage-projection-state.json" >/dev/null ||
  die "PostgreSQL did not retain Redis-pending projection state"

current_stage="restore Redis and repair its disposable snapshot"
scale_deployment redis "$original_redis_replicas"
redis_scaled=false
wait_deployment_replicas market-data-projection 1 || die "projection did not recover after Redis"
wait_for_redis_repair || die "Redis snapshot was not repaired from PostgreSQL"

current_stage="verify failure isolation"
capture_consumer_state "$evidence_dir/critical-after.json" ||
  die "cannot capture critical consumer state after replay"
matching_ready="$(
  kns get pods -l app.kubernetes.io/name=matching -o json |
    jq '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | length'
)"
critical_ready="$(jq -e '
  .persistenceQuarantines == 0
  and .accountQuarantines == 0
  and .quickfixQuarantines == 0
' "$evidence_dir/critical-after.json" >/dev/null && echo true || echo false)"
jq -n \
  --argjson matchingReady "$matching_ready" \
  --argjson criticalConsumersReady "$critical_ready" \
  '{redisRepaired:true,projectionReady:true,streamerReady:true,
    matchingReady:$matchingReady,criticalConsumersReady:$criticalConsumersReady}' \
  >"$evidence_dir/restoration.json"

current_stage="evaluate retained evidence"
pending_verdict="$evidence_dir/verdict.pending.json"
"$evidence_verifier" market-data "$evidence_dir" "$pending_verdict" ||
  die "market-data evidence did not satisfy the deterministic verdict"

current_stage="restore certification environment"
restore_environment
[[ "$restoration_failed" == false ]] || die "environment restoration failed"
mv "$pending_verdict" "$evidence_dir/verdict.json"
certification_succeeded=true
current_stage="completed"
printf 'Market-data certification passed: %s\n' "$evidence_dir/verdict.json"

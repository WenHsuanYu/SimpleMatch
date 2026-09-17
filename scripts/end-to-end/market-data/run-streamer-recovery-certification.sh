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
# shellcheck source=scripts/end-to-end/market-data/lib/streamer-recovery-lifecycle.sh
source "$repo_root/scripts/end-to-end/market-data/lib/streamer-recovery-lifecycle.sh"
# shellcheck source=scripts/end-to-end/market-data/lib/streamer-recovery-verdict.sh
source "$repo_root/scripts/end-to-end/market-data/lib/streamer-recovery-verdict.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="kind-$cluster_name"
namespace=""
evidence_dir=""
timeout_seconds="${SIMPLEMATCH_MARKET_DATA_RECOVERY_TIMEOUT_SECONDS:-300}"
retained_evidence_dir=""
current_stage="preflight"
failure_reason=""
evidence_initialized=false
certification_succeeded=false
restoration_failed=false

streamer_port_forward_pid=""
streamer_port=""
projection_port_forward_pid=""
projection_port=""
observer_pid=""
original_projection_replicas=""
projection_scaled=false
projection_environment_modified=false
temporary_streamer_selector=false
operator_token=""
streamer_topic_partition_count=""
streamer_expected_assignment=""

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/end-to-end/market-data/run-streamer-recovery-certification.sh \
    --namespace NAME \
    --evidence-dir PATH \
    [--timeout-seconds N]

Certifies one market-data streamer owner across a Recreate replacement. The same public gRPC
client must receive a snapshot, observe its stream ending, reconnect with the same subscription,
and receive a second snapshot. The namespace must be lifecycle-labelled disposable.
EOF_USAGE
}

die() {
  failure_reason="$*"
  printf 'market-data streamer recovery: %s\n' "$failure_reason" >&2
  exit 1
}

on_error() {
  local status="$1"
  [[ -n "$failure_reason" ]] ||
    failure_reason="unexpected command failure during $current_stage"
  return "$status"
}
trap 'on_error $?' ERR

stop_streamer_port_forward() {
  stop_background_process "${streamer_port_forward_pid:-}"
  streamer_port_forward_pid=""
  streamer_port=""
}

stop_projection_port_forward() {
  stop_background_process "${projection_port_forward_pid:-}"
  projection_port_forward_pid=""
  projection_port=""
}

stop_observer() {
  stop_background_process "${observer_pid:-}"
  observer_pid=""
}

restore_streamer_selector() {
  [[ "$temporary_streamer_selector" == true ]] || return 0
  kns patch deployment/marketdata-streamer --type=json \
    -p='[{"op":"remove","path":"/spec/template/spec/nodeSelector/kubernetes.io~1hostname"}]' \
    >/dev/null || restoration_failed=true
  temporary_streamer_selector=false
  kns rollout status deployment/marketdata-streamer \
    --timeout="${timeout_seconds}s" >/dev/null || restoration_failed=true
}

restore_projection_environment() {
  if [[ "$projection_environment_modified" == true ]]; then
    kns set env deployment/market-data-projection \
      SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_HTTP_ENABLED- \
      SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_OPERATOR_TOKEN- >/dev/null 2>&1 ||
      restoration_failed=true
    projection_environment_modified=false
  fi
  if [[ "$projection_scaled" == true && -n "$original_projection_replicas" ]]; then
    scale_deployment market-data-projection "$original_projection_replicas" ||
      restoration_failed=true
    projection_scaled=false
  elif [[ -n "$original_projection_replicas" ]]; then
    kns rollout status deployment/market-data-projection \
      --timeout="${timeout_seconds}s" >/dev/null 2>&1 || restoration_failed=true
  fi
}

restore_environment() {
  set +e
  stop_observer
  stop_streamer_port_forward
  stop_projection_port_forward
  restore_streamer_selector
  restore_projection_environment
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
  restore_environment
  if [[ "$restoration_failed" == true ]]; then
    status=1
    [[ -n "$failure_reason" ]] || failure_reason="environment restoration failed"
  fi
  if ((status != 0)) || [[ "$certification_succeeded" != true ]]; then
    write_failure_verdict "$status"
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'failure_reason="certification interrupted during $current_stage"; exit 130' INT TERM

wait_for_signal() {
  local signal_file="$1"
  for _ in $(seq 1 "$timeout_seconds"); do
    [[ -s "$signal_file" ]] && return 0
    if [[ -n "$observer_pid" ]] && ! kill -0 "$observer_pid" >/dev/null 2>&1; then
      cat "$evidence_dir/diagnostics/streamer-client.log" >&2 || true
      return 1
    fi
    sleep 1
  done
  return 1
}

streamer_pods_json() {
  kns get pods -l app.kubernetes.io/name=marketdata-streamer -o json
}

streamer_pod_json() {
  streamer_pods_json |
    jq -e 'if (.items | length) == 1 then .items[0] else error("expected exactly one streamer Pod") end'
}

wait_for_streamer_ready_on_node() {
  local expected_node="$1"
  local old_uid="$2"
  for _ in $(seq 1 "$timeout_seconds"); do
    local pod
    pod="$(streamer_pods_json)"
    if jq -e --arg node "$expected_node" --arg old_uid "$old_uid" '
        (.items | length) == 1
        and .items[0].metadata.uid != $old_uid
        and .items[0].spec.nodeName == $node
        and any(.items[0].status.conditions[]?; .type == "Ready" and .status == "True")
      ' <<<"$pod" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_old_streamer_pod_to_disappear() {
  local old_pod="$1"
  for _ in $(seq 1 "$timeout_seconds"); do
    if ! kns get pod "$old_pod" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

start_streamer_client() {
  local initial_subscription="$evidence_dir/signals/initial-subscription.ready"
  local initial_snapshot="$evidence_dir/signals/initial-snapshot.ready"
  local disconnected="$evidence_dir/signals/disconnected.ready"
  local replacement_ready="$evidence_dir/signals/replacement.ready"
  local reconnected="$evidence_dir/signals/reconnected.ready"
  local resubscribed_snapshot="$evidence_dir/signals/resubscribed-snapshot.ready"
  SIMPLEMATCH_MARKET_DATA_HOST=127.0.0.1 \
    SIMPLEMATCH_MARKET_DATA_PORT="$streamer_port" \
    SIMPLEMATCH_MARKET_DATA_VENUE_MIC="$venue_mic" \
    SIMPLEMATCH_MARKET_DATA_SYMBOL="$symbol" \
    SIMPLEMATCH_MARKET_DATA_TIMEOUT_SECONDS="$timeout_seconds" \
    SIMPLEMATCH_MARKET_DATA_RECOVERY_EVIDENCE="$evidence_dir/client-recovery.json" \
    SIMPLEMATCH_MARKET_DATA_INITIAL_SUBSCRIPTION_READY="$initial_subscription" \
    SIMPLEMATCH_MARKET_DATA_INITIAL_SNAPSHOT_READY="$initial_snapshot" \
    SIMPLEMATCH_MARKET_DATA_DISCONNECTED_READY="$disconnected" \
    SIMPLEMATCH_MARKET_DATA_REPLACEMENT_READY="$replacement_ready" \
    SIMPLEMATCH_MARKET_DATA_RECONNECTED_READY="$reconnected" \
    SIMPLEMATCH_MARKET_DATA_RESUBSCRIBED_SNAPSHOT_READY="$resubscribed_snapshot" \
    GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/simplematch-gradle-cache}" \
    "$repo_root/gradlew" --no-daemon -q -Dkotlin.compiler.execution.strategy=in-process \
      :tools:risk-matching-e2e-verifier:observeMarketDataRecovery \
      >"$evidence_dir/diagnostics/streamer-client.log" 2>&1 &
  observer_pid="$!"
}

start_projection_replay_interface() {
  local existing_override_count
  existing_override_count="$(
    kns get deployment market-data-projection -o json |
      jq '[.spec.template.spec.containers[] | select(.name == "market-data-projection")
        | .env[]? | select(.name == "SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_HTTP_ENABLED"
          or .name == "SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_OPERATOR_TOKEN")] | length'
  )"
  ((existing_override_count == 0)) || die "projection already defines replay environment overrides"
  operator_token="$(tr -d '-' </proc/sys/kernel/random/uuid)"
  projection_scaled=true
  scale_deployment market-data-projection 1
  kns set env deployment/market-data-projection \
    SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_HTTP_ENABLED=true \
    SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_OPERATOR_TOKEN="$operator_token" >/dev/null
  projection_environment_modified=true
  wait_deployment_replicas market-data-projection 1 ||
    die "projection replay adapter is not ready"
  start_projection_port_forward "$evidence_dir" setup ||
    die "projection management port-forward did not become ready"
}

reset_projection_state() {
  local phase="$1"
  local response="$evidence_dir/projection/${phase}-reset-response.json"
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
  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka:9092 \
    --group market-data-projection \
    --topic matching.events \
    --reset-offsets --to-earliest --execute \
    >"$evidence_dir/projection/${phase}-offset-reset.txt" \
    2>"$evidence_dir/projection/${phase}-offset-reset.stderr"
}

capture_streamer_group() {
  local destination="$1"
  local broker
  broker="$(kafka_pod)"
  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka:9092 \
    --describe --group marketdata-streamer --members --verbose \
    >"$destination" 2>"$destination.stderr"
}

capture_streamer_topic_profile() {
  local destination="$1"
  local broker
  broker="$(kafka_pod)"
  kns exec "$broker" -c kafka -- /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:9092 --describe --topic marketdata.events \
    >"$destination" 2>"$destination.stderr" || return 1
  streamer_topic_partition_count="$(awk '
    { for (field = 1; field <= NF; field++) {
        if ($field == "PartitionCount:") { print $(field + 1); exit }
      }
    }
  ' "$destination")"
  [[ "$streamer_topic_partition_count" =~ ^[0-9]+$ ]] || return 1
  [[ "$streamer_topic_partition_count" == 15 ]] || return 1
  streamer_expected_assignment="marketdata.events:$(seq -s, 0 $((streamer_topic_partition_count - 1)))"
}

wait_for_complete_streamer_group() {
  local destination="$1"
  local expected_assignment="$streamer_expected_assignment"
  for _ in $(seq 1 "$timeout_seconds"); do
    capture_streamer_group "$destination" || true
    if awk -v expected="$expected_assignment" \
      -v expectedPartitions="$streamer_topic_partition_count" '
        NR == 1 { next }
        $1 == "marketdata-streamer" {
          rows++
          if ($5 != expectedPartitions || $7 != expected) {
            invalid=1
          }
        }
        END { exit !(rows == 1 && invalid != 1) }
      ' "$destination"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

write_runtime_verdict() {
  local old_uid="$1"
  local old_node="$2"
  local new_uid="$3"
  local new_node="$4"
  local old_gone_epoch_ms="$5"
  local new_ready_epoch_ms="$6"
  jq -n \
    --arg sourceRevision "$(git -C "$repo_root" rev-parse HEAD)" \
    --arg namespace "$namespace" \
    --arg context "$context" \
    --arg oldUid "$old_uid" \
    --arg oldNode "$old_node" \
    --arg newUid "$new_uid" \
    --arg newNode "$new_node" \
    --argjson oldPodGoneEpochMs "$old_gone_epoch_ms" \
    --argjson newPodReadyEpochMs "$new_ready_epoch_ms" \
    --argjson expectedPartitions "$streamer_topic_partition_count" \
    '{sourceRevision:$sourceRevision,namespace:$namespace,context:$context,
      oldUid:$oldUid,oldNode:$oldNode,newUid:$newUid,newNode:$newNode,
      oldPodGoneEpochMs:$oldPodGoneEpochMs,newPodReadyEpochMs:$newPodReadyEpochMs,
      expectedPartitions:$expectedPartitions}' |
    write_streamer_recovery_verdict "$evidence_dir/verdict.pending.json"
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
((timeout_seconds <= 600)) || die "--timeout-seconds must not exceed 600"
for tool in docker kubectl jq curl awk grep date seq sleep tr git; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
[[ "$(kubectl config current-context)" == "$context" ]] ||
  die "current Kubernetes context must be $context"
retained_evidence_dir="$(simplematch_production_like_evidence_dir "$repo_root")"
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
mkdir -p "$evidence_dir/diagnostics" "$evidence_dir/signals" "$evidence_dir/projection"
evidence_initialized=true

docker info >/dev/null || die "Docker daemon is not ready"
simplematch_kind_exists "$cluster_name" || die "canonical kind cluster is not available"
simplematch_kind_validate_canonical_topology \
  "$context" "$evidence_dir/diagnostics/nodes.json" ||
  die "canonical kind topology is not one Ready control plane plus three workers"
simplematch_kind_validate_control_plane_stability \
  "$context" 5 60 "$evidence_dir/diagnostics/control-plane" 60 ||
  die "canonical Kubernetes control plane is not stable before streamer fault injection"
simplematch_certification_verifier_image \
  "$repo_root" "$namespace" "$retained_evidence_dir" >/dev/null ||
  die "retained production-like source or verifier-image provenance is not valid"
retained_run_id="$(awk -F= '$1 == "run_id" {print substr($0, index($0, "=") + 1)}' \
  "$retained_evidence_dir/run-context")"
namespace_run_id="$(kubectl --context "$context" get namespace "$namespace" \
  -o jsonpath='{.metadata.labels.simplematch\.io/run-id}')"
[[ -n "$retained_run_id" && "$namespace_run_id" == "$retained_run_id" ]] ||
  die "namespace run-id does not match retained production-like evidence"
expected_streamer_image="$(simplematch_local_image_lock_digest_reference \
  "$retained_evidence_dir/local-images.lock" marketdata-streamer)" ||
  die "retained market-data streamer image lock is unavailable"
actual_streamer_image="$(kubectl --context "$context" -n "$namespace" \
  get deployment marketdata-streamer -o json |
  jq -er '.spec.template.spec.containers[]
    | select(.name == "marketdata-streamer") | .image')" ||
  die "deployed market-data streamer image is unavailable"
[[ "$actual_streamer_image" == "$expected_streamer_image" ]] ||
  die "deployed market-data streamer image does not match retained digest lock"

current_stage="capture baseline streamer owner"
original_projection_replicas="$(workload_replicas deployment market-data-projection)"
((original_projection_replicas > 0)) || die "market-data projection must be running"
start_streamer_port_forward "$evidence_dir" initial ||
  die "market-data streamer port-forward did not become ready"
streamer_pod_json >"$evidence_dir/streamer-before.json"
old_uid="$(jq -er '.metadata.uid' "$evidence_dir/streamer-before.json")"
old_pod="$(jq -er '.metadata.name' "$evidence_dir/streamer-before.json")"
old_node="$(jq -er '.spec.nodeName' "$evidence_dir/streamer-before.json")"
venue_mic="$(kns exec "$(postgres_pod)" -c postgres -- psql -U simplematch -d simplematch -At \
  -v ON_ERROR_STOP=1 -c "SELECT TRIM(venue_mic) FROM market_data_projection.instrument_market_data ORDER BY updated_at_unix_ms DESC, venue_mic, symbol LIMIT 1;")"
symbol="$(kns exec "$(postgres_pod)" -c postgres -- psql -U simplematch -d simplematch -At \
  -v ON_ERROR_STOP=1 -c "SELECT symbol FROM market_data_projection.instrument_market_data ORDER BY updated_at_unix_ms DESC, venue_mic, symbol LIMIT 1;")"
[[ "$venue_mic" =~ ^[A-Z0-9]{4}$ && -n "$symbol" ]] || die "projection has no retained instrument"

current_stage="prepare public client and replay interface"
start_projection_replay_interface
start_streamer_client
wait_for_signal "$evidence_dir/signals/initial-subscription.ready" ||
  die "public market-data client did not issue its initial subscription"
produce_projection_snapshot "$evidence_dir" initial
wait_for_signal "$evidence_dir/signals/initial-snapshot.ready" ||
  die "public market-data client did not receive the initial snapshot"
capture_streamer_topic_profile "$evidence_dir/topic-profile.txt" ||
  die "deployed marketdata.events topic does not expose the expected partition profile"
wait_for_complete_streamer_group "$evidence_dir/group-before.txt" ||
  die "streamer did not own all expected marketdata.events partitions"

current_stage="replace streamer on another worker"
target_node="$(kubectl --context "$context" get nodes \
  -l simplematch.io/node-pool=local-resilience -o json |
  jq -er --arg old_node "$old_node" '
    [.items[]
      | select(.metadata.name != $old_node)
      | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
      | .metadata.name]
    | sort | .[0]')" || die "no alternate Ready local-resilience worker exists"
selector_patch="$(jq -cn --arg node "$target_node" \
  '[{op:"add",path:"/spec/template/spec/nodeSelector/kubernetes.io~1hostname",value:$node}]')"
kns patch deployment/marketdata-streamer --type=json -p="$selector_patch" >/dev/null
temporary_streamer_selector=true
wait_for_old_streamer_pod_to_disappear "$old_pod" ||
  die "old streamer Pod did not disappear before replacement"
old_gone_epoch_ms="$(date +%s%3N)"
wait_for_streamer_ready_on_node "$target_node" "$old_uid" ||
  die "streamer replacement did not become Ready on the alternate worker"
new_ready_epoch_ms="$(date +%s%3N)"
streamer_pod_json >"$evidence_dir/streamer-after.json"
new_uid="$(jq -er '.metadata.uid' "$evidence_dir/streamer-after.json")"
new_node="$(jq -er '.spec.nodeName' "$evidence_dir/streamer-after.json")"
[[ "$new_node" == "$target_node" && "$new_uid" != "$old_uid" ]] ||
  die "streamer replacement identity or placement did not change"
publish_streamer_replacement_ready "$evidence_dir"
wait_for_signal "$evidence_dir/signals/disconnected.ready" ||
  die "public client did not observe the original stream termination"
wait_for_signal "$evidence_dir/signals/reconnected.ready" ||
  die "public client did not issue the reconnect subscription"

current_stage="verify resubscription and full-stream ownership"
produce_projection_snapshot "$evidence_dir" resubscribed
wait_for_signal "$evidence_dir/signals/resubscribed-snapshot.ready" ||
  die "public client did not receive a snapshot after resubscription"
wait_for_complete_streamer_group "$evidence_dir/group-after.txt" ||
  die "replacement streamer did not reacquire all expected marketdata.events partitions"
wait "$observer_pid" || {
  cat "$evidence_dir/diagnostics/streamer-client.log" >&2
  die "public market-data recovery observer failed"
}
observer_pid=""
jq -e '
  (.connections | length) >= 2
  and (.connections[0].snapshotObserved == true)
  and (.connections[1].snapshotObserved == true)
  and (.snapshots | length) == 2
' "$evidence_dir/client-recovery.json" >/dev/null ||
  die "client recovery evidence does not prove two subscriptions and two snapshots"

write_runtime_verdict "$old_uid" "$old_node" "$new_uid" "$new_node" \
  "$old_gone_epoch_ms" "$new_ready_epoch_ms"

current_stage="restore certification environment"
restore_environment
[[ "$restoration_failed" == false ]] || die "environment restoration failed"
mv "$evidence_dir/verdict.pending.json" "$evidence_dir/verdict.json"
certification_succeeded=true
current_stage="completed"
printf 'Market-data streamer recovery certification passed: %s\n' "$evidence_dir/verdict.json"

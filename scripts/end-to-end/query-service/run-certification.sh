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
# shellcheck source=scripts/end-to-end/query-service/lib/verdict.sh
source "$script_dir/lib/verdict.sh"
# shellcheck source=scripts/end-to-end/query-service/lib/evidence.sh
source "$script_dir/lib/evidence.sh"
# shellcheck source=scripts/end-to-end/query-service/lib/run-lifecycle.sh
source "$script_dir/lib/run-lifecycle.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="kind-$cluster_name"
namespace=""
evidence_dir=""
retained_evidence_dir=""
timeout_seconds="${SIMPLEMATCH_QUERY_CERTIFICATION_TIMEOUT_SECONDS:-180}"
current_stage="preflight"
failure_reason=""
evidence_initialized=false
restoration_failed=false
certification_succeeded=false
query_port_forward_pid=""
query_port=""
replay_boundary_dir=""
original_query_replicas=""
original_redis_replicas=""
verifier_image_reference=""
query_environment_modified=false
query_scaled=false
redis_scaled=false
operator_token=""
order_id=""
account_id=""
trading_day=""
venue_mic=""
symbol=""

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/end-to-end/query-service/run-certification.sh \
    --namespace NAME \
    --retained-evidence-dir PATH \
    --evidence-dir PATH \
    [--timeout-seconds N]

Certifies deterministic query-service replay, PostgreSQL fallback, Redis
rebuild, freshness metadata, and critical-path isolation in a retained
production-like namespace.
EOF_USAGE
}

die() {
  failure_reason="$*"
  printf 'query-service certification: %s\n' "$failure_reason" >&2
  exit 1
}

on_error() {
  local status="$1"
  [[ -n "$failure_reason" ]] ||
    failure_reason="unexpected command failure during $current_stage"
  return "$status"
}
trap 'on_error $?' ERR

trap cleanup_query_certification EXIT
trap 'failure_reason="certification interrupted during $current_stage"; exit 130' INT TERM

start_query_port_forward() {
  start_port_forward service/query-service 8086 \
    "$evidence_dir/diagnostics/query-port-forward.log" \
    query_port_forward_pid query_port ||
    die "query-service port-forward did not become ready"
}

run_matching_fixture() {
  local trading_day buy_account_id sell_account_id
  local fixture_dir="$evidence_dir/matching-fixture"
  trading_day="$(kns get configmap matching-session-config -o jsonpath='{.data.trading_day}')"
  [[ "$trading_day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || return 1
  [[ -r /proc/sys/kernel/random/uuid ]] || return 1
  buy_account_id="$(cat /proc/sys/kernel/random/uuid)"
  sell_account_id="$(cat /proc/sys/kernel/random/uuid)"
  mkdir -p "$fixture_dir"

  # Two public RM-1 orders share the artifact-selected instrument and price. The BUY rests with a
  # cash reservation; the SELL reserves a long position and crosses it, producing final matching
  # and Account lifecycle events without direct writes to either service's reservation tables.
  bash "$repo_root/scripts/run-risk-matching-command-e2e.sh" \
    --namespace "$namespace" \
    --trading-day "$trading_day" \
    --evidence-dir "$fixture_dir/buy" \
    --account-id "$buy_account_id" \
    --side BUY \
    --verifier-image "$verifier_image_reference" \
    --retained-evidence-dir "$retained_evidence_dir"
  wait_for_rm1_helper_cleanup || return 1
  bash "$repo_root/scripts/run-risk-matching-command-e2e.sh" \
    --namespace "$namespace" \
    --trading-day "$trading_day" \
    --evidence-dir "$fixture_dir/sell" \
    --account-id "$sell_account_id" \
    --side SELL \
    --verifier-image "$verifier_image_reference" \
    --retained-evidence-dir "$retained_evidence_dir"
  wait_for_rm1_helper_cleanup
}

wait_for_rm1_helper_cleanup() {
  for _ in $(seq 1 60); do
    if ! kns get job/risk-matching-e2e-verifier >/dev/null 2>&1 &&
      ! kns get configmap/risk-matching-e2e-run >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

reset_query_state() {
  local response="$evidence_dir/reset-response.json"
  local status
  status="$(curl --connect-timeout 2 --max-time 30 -sS -o "$response" -w '%{http_code}' \
    -X POST -H "X-SimpleMatch-Query-Token: $operator_token" \
    "http://127.0.0.1:${query_port}/internal/query/rebuild")" || return 1
  [[ "$status" == 200 ]] || return 1
  jq -e '.status == "RESET_COMPLETE"' "$response" >/dev/null
}

while (($# > 0)); do
  case "$1" in
    --namespace) namespace="${2:?--namespace requires a value}"; shift 2 ;;
    --retained-evidence-dir)
      retained_evidence_dir="${2:?--retained-evidence-dir requires a value}"
      shift 2
      ;;
    --evidence-dir) evidence_dir="${2:?--evidence-dir requires a value}"; shift 2 ;;
    --timeout-seconds)
      timeout_seconds="${2:?--timeout-seconds requires a value}"
      shift 2
      ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

[[ -n "$namespace" ]] || die "--namespace is required"
[[ -n "$retained_evidence_dir" ]] || die "--retained-evidence-dir is required"
[[ -n "$evidence_dir" ]] || die "--evidence-dir is required"
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die "--timeout-seconds must be positive"
(( timeout_seconds <= 300 )) || die "--timeout-seconds must not exceed 300"
for tool in kubectl docker jq curl git awk sed grep date seq sleep tr cp mv cat wc; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
[[ "$(kubectl config current-context)" == "$context" ]] ||
  die "current Kubernetes context must be $context"
kubectl get namespace "$namespace" >/dev/null 2>&1 || die "namespace does not exist"
simplematch_kind_namespace_is_disposable "$context" "$namespace" ||
  die "refusing certification outside a lifecycle-labelled disposable namespace"

mkdir -p "$evidence_dir"
evidence_dir="$(cd -- "$evidence_dir" && pwd)"
shopt -s nullglob dotglob
existing_evidence=("$evidence_dir"/*)
shopt -u nullglob dotglob
((${#existing_evidence[@]} == 0)) || die "evidence directory must be empty"
mkdir -p "$evidence_dir/diagnostics"
evidence_initialized=true

current_stage="validate retained production-like provenance"
retained_evidence_dir="$(cd -- "$retained_evidence_dir" && pwd)" ||
  die "cannot resolve retained evidence directory"
verifier_image_reference="$(simplematch_certification_verifier_image \
  "$repo_root" "$namespace" "$retained_evidence_dir")" ||
  die "retained production-like source or image provenance is invalid"

current_stage="validate query runtime"
original_query_replicas="$(workload_replicas deployment query-service)"
original_redis_replicas="$(workload_replicas deployment redis)"
(( original_query_replicas > 0 )) || die "query-service must be running"
(( original_redis_replicas == 1 )) || die "expected one Redis replica"

current_stage="establish execution-backed query fixture"
run_matching_fixture || die "Matching execution fixture could not be established"
wait_for_query_fixture || die "query projection did not expose the Matching fixture"

current_stage="capture baseline"
capture_consumer_state "$evidence_dir/critical-before.json" ||
  die "cannot capture critical consumer baseline"
require_clean_baseline "$evidence_dir/critical-before.json" ||
  die "critical consumer baseline is not clean"
order_id="$(jq -er '.orderId | select(length > 0)' "$evidence_dir/selected-fixture.json")"
account_id="$(jq -er '.accountId | select(length > 0)' "$evidence_dir/selected-fixture.json")"
trading_day="$(jq -er '.tradingDay | select(length == 10)' "$evidence_dir/selected-fixture.json")"
venue_mic="$(jq -er '.venueMic | select(length == 4)' "$evidence_dir/selected-fixture.json")"
symbol="$(jq -er '.symbol | select(length > 0)' "$evidence_dir/selected-fixture.json")"
kns get deployment query-service redis -o json >"$evidence_dir/deployments-before.json"
jq -n \
  --arg sourceRevision "$(git -C "$repo_root" rev-parse HEAD)" \
  --arg namespace "$namespace" \
  --arg retainedEvidenceDir "$retained_evidence_dir" \
  '{sourceRevision:$sourceRevision,namespace:$namespace,
    retainedEvidenceDir:$retainedEvidenceDir}' >"$evidence_dir/provenance.json"
start_query_port_forward
capture_query_snapshot "$evidence_dir/baseline.json" || die "cannot capture baseline query views"

current_stage="verify PostgreSQL fallback during Redis outage"
redis_scaled=true
scale_deployment redis 0 || die "Redis could not be scaled down for fallback verification"
capture_query_snapshot "$evidence_dir/redis-outage.json" ||
  die "query APIs did not fall back to PostgreSQL while Redis was unavailable"
scale_deployment redis "$original_redis_replicas" ||
  die "Redis could not be restored after fallback verification"
redis_scaled=false

current_stage="verify critical paths during query-service outage"
stop_query_port_forward
query_scaled=true
scale_deployment query-service 0 || die "query-service could not be stopped for isolation verification"
capture_query_service_outage_state "$evidence_dir/query-outage.json" ||
  die "query-service outage was not observed"
capture_consumer_state "$evidence_dir/critical-during-query-outage.json" ||
  die "cannot capture critical state while query-service is unavailable"
scale_deployment query-service "$original_query_replicas" ||
  die "query-service could not be restored after isolation verification"
query_scaled=false
start_query_port_forward

current_stage="enable bounded query replay"
existing_override_count="$(
  kns get deployment query-service -o json |
    jq '[.spec.template.spec.containers[] | select(.name == "query-service")
      | .env[]? | select(.name == "SIMPLEMATCH_QUERY_SERVICE_REBUILD_HTTP_ENABLED"
        or .name == "SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN")] | length'
)"
(( existing_override_count == 0 )) || die "query-service already defines rebuild overrides"
operator_token="$(tr -d '-' </proc/sys/kernel/random/uuid)"
query_scaled=true
scale_deployment query-service 1 || die "query-service could not be scaled for rebuild"
query_environment_modified=true
kns set env deployment/query-service \
  SIMPLEMATCH_QUERY_SERVICE_REBUILD_HTTP_ENABLED=true \
  SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN="$operator_token" >/dev/null
wait_deployment_replicas query-service 1 || die "query rebuild adapter is not ready"
stop_query_port_forward
start_query_port_forward

current_stage="reset and replay retained query sources"
replay_boundary_dir="$evidence_dir/replay-boundary"
capture_query_replay_boundary "$replay_boundary_dir" ||
  die "query topic replay boundaries could not be captured"
reset_query_state || die "query projection reset failed"
reset_query_consumer_group query-service-matching-events matching.events \
  "$replay_boundary_dir/matching.events.start.json" ||
  die "matching.events query offsets could not be reset"
reset_query_consumer_group query-service-account-lifecycle account.lifecycle \
  "$replay_boundary_dir/account.lifecycle.start.json" ||
  die "account.lifecycle query offsets could not be reset"
kns rollout restart deployment/query-service >/dev/null
wait_deployment_replicas query-service 1 || die "query-service did not restart"
stop_query_port_forward
start_query_port_forward
wait_for_query_snapshot "$evidence_dir/rebuilt.json" ||
  die "rebuilt query views did not converge before timeout"

current_stage="verify Redis rebuild and failure isolation"
query_redis_keys_present || die "public reads did not rebuild the selected Redis keys"
capture_consumer_state "$evidence_dir/critical-after.json" ||
  die "cannot capture critical consumer state after query replay"
matching_ready="$(
  kns get pods -l app.kubernetes.io/name=matching -o json |
    jq '[.items[] | select(any(.status.conditions[]?;
      .type == "Ready" and .status == "True"))] | length'
)"
critical_ready="$(jq -e '
  .persistenceQuarantines == 0
  and .accountQuarantines == 0
  and .quickfixQuarantines == 0
  and .riskQuarantines == 0
  and .marketDataDeadLetters == 0
' "$evidence_dir/critical-after.json" >/dev/null && echo true || echo false)"
jq -e '.queryPodCount == 0' "$evidence_dir/query-outage.json" >/dev/null ||
  die "query-service outage evidence is invalid"
jq -n \
  --argjson matchingReady "$matching_ready" \
  --argjson criticalConsumersReady "$critical_ready" \
  --argjson queryOutageObserved true \
  '{redisKeysPresent:true,queryServiceReady:true,queryOutageObserved:$queryOutageObserved,
    matchingReady:$matchingReady,criticalConsumersReady:$criticalConsumersReady}' \
  >"$evidence_dir/restoration.json"

current_stage="evaluate retained evidence"
pending_verdict="$evidence_dir/verdict.pending.json"
evaluate_query_service_verdict "$evidence_dir" "$pending_verdict" ||
  die "query-service evidence did not satisfy the deterministic verdict"

current_stage="restore certification environment"
restore_query_certification_environment
[[ "$restoration_failed" == false ]] || die "environment restoration failed"
mv "$pending_verdict" "$evidence_dir/verdict.json"
certification_succeeded=true
current_stage="completed"
printf 'Query-service certification passed: %s\n' "$evidence_dir/verdict.json"

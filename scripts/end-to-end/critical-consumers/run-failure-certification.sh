#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$repo_root/scripts/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$repo_root/scripts/lib/local-kind.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/matching-status.sh
source "$script_dir/lib/matching-status.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/failure-support.sh
source "$script_dir/lib/failure-support.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/system-observation.sh
source "$script_dir/lib/system-observation.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="kind-$cluster_name"
namespace=""
evidence_dir=""
timeout_seconds="${SIMPLEMATCH_CRITICAL_CONSUMER_FAILURE_TIMEOUT_SECONDS:-180}"

observer_pod="matching-event-outage-observer"
observer_manifest="$repo_root/deploy/k8s/verification/matching-event-observer-pod.yaml"
kafka_observer_pod="critical-consumer-kafka-observer"
kafka_observer_manifest="$repo_root/deploy/k8s/verification/critical-consumer-kafka-observer-pod.yaml"
kafka_observer_port_forward_pid=""
kafka_observer_port=""
fix_port_forward_pid=""
fix_port=""
fix_submit_pid=""
fix_ready_file=""
fix_release_file=""
fix_submit_log=""
connect_port_forward_pid=""
connect_port=""
gateway_port_forward_pid=""
gateway_port=""
gateway_operator_token=""
gateway_env_modified=false
outbox_connector_paused=false
original_matching_replicas=""
original_postgres_replicas=""
original_account_replicas=""
original_persistence_replicas=""
original_quickfix_replicas=""
restoration_failed=false
evidence_initialized=false
current_stage="preflight"
failure_reason=""

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/end-to-end/critical-consumers/run-failure-certification.sh \
    --namespace NAME \
    --evidence-dir PATH \
    [--timeout-seconds N]

Runs the production-like dependency-outage certification for the three critical
matching.events consumers and retained FIX delivery.

The FIX client logs on before Gateway admission and waits at a submission
barrier. The runner then collects fresh, stable readiness observations, opens
Gateway admission, and immediately releases one NewOrderSingle. The Risk outbox
connector is intentionally paused as a deterministic publication barrier.

The namespace must be lifecycle-labeled disposable and dedicated to this run.
Workload replicas, Gateway test configuration, and the Risk outbox connector are
restored on exit. A failed run writes verdict.json with the failed stage.
EOF_USAGE
}

die() {
  failure_reason="$*"
  printf 'critical consumer failure certification: %s\n' "$failure_reason" >&2
  exit 1
}

on_error() {
  local status="$1"
  if [[ -z "$failure_reason" ]]; then
    failure_reason="unexpected command failure during $current_stage"
  fi
  return "$status"
}
trap 'on_error $?' ERR

write_failure_verdict() {
  local status="$1"
  [[ "$evidence_initialized" == true ]] || return 0
  [[ -f "$evidence_dir/verdict.json" ]] && return 0
  jq -n \
    --arg status FAIL \
    --arg namespace "$namespace" \
    --arg stage "$current_stage" \
    --arg reason "${failure_reason:-unexpected command failure}" \
    --argjson exitStatus "$status" \
    --argjson restorationFailed "$([[ "$restoration_failed" == true ]] && echo true || echo false)" \
    '{
      status:$status,
      namespace:$namespace,
      stage:$stage,
      reason:$reason,
      exitStatus:$exitStatus,
      restorationFailed:$restorationFailed
    }' >"$evidence_dir/verdict.json"
}

cleanup() {
  local status="$?"
  trap - ERR EXIT
  set +e

  stop_background_process "${fix_submit_pid:-}"
  fix_submit_pid=""
  stop_fix_port_forward
  stop_gateway_port_forward
  stop_kafka_observation_adapter
  kns delete pod "$kafka_observer_pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kns delete pod "$observer_pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true

  if [[ "$outbox_connector_paused" == true && -n "$connect_port" ]]; then
    connect_request PUT /connectors/risk-service-outbox/resume \
      "$evidence_dir/diagnostics/outbox-cleanup-resume.txt" >/dev/null 2>&1 ||
      restoration_failed=true
    outbox_connector_paused=false
  fi
  stop_connect_port_forward
  restore_gateway_environment
  restore_workloads

  if (( status != 0 )) || [[ "$restoration_failed" == true ]]; then
    collect_diagnostics
  fi
  if [[ "$restoration_failed" == true ]]; then
    status=1
    [[ -n "$failure_reason" ]] || failure_reason='environment restoration failed'
  fi
  if (( status != 0 )); then
    write_failure_verdict "$status"
  fi
  exit "$status"
}
trap cleanup EXIT

submit_open_eligible_observation() {
  local check="$1"
  local observation="$evidence_dir/baseline/gateway-observation-$check.json"
  local final_response="$evidence_dir/baseline/gateway-observation-$check-response.json"
  local gateway_attempt

  for gateway_attempt in 1 2 3; do
    local response="$evidence_dir/baseline/gateway-observation-${check}-gateway-attempt-${gateway_attempt}.json"
    capture_gateway_observation "$check" "$observation" || return 1
    gateway_request POST /operations/observations "$response" "$observation" || return 1
    if jq -e '.readiness == "OPEN_ELIGIBLE"' "$response" >/dev/null; then
      cp "$response" "$final_response"
      return 0
    fi
    if gateway_response_is_retryable_stale "$response"; then
      sleep 0.2
      continue
    fi
    jq . "$response" >&2
    return 1
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

[[ -n "$namespace" ]] || die '--namespace is required'
[[ -n "$evidence_dir" ]] || die '--evidence-dir is required'
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die '--timeout-seconds must be a positive integer'
(( timeout_seconds <= 300 )) || die '--timeout-seconds must not exceed 300'

for tool in kubectl jq curl awk sed grep date seq sleep tr cp; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
[[ -x "$repo_root/gradlew" ]] || die 'Gradle wrapper is missing'
[[ -f "$observer_manifest" ]] || die "observer Pod manifest is missing: $observer_manifest"
[[ -f "$kafka_observer_manifest" ]] ||
  die "Kafka observer Pod manifest is missing: $kafka_observer_manifest"
[[ -r /proc/sys/kernel/random/uuid ]] || die '/proc/sys/kernel/random/uuid is required'
[[ "$(kubectl config current-context)" == "$context" ]] ||
  die "current Kubernetes context must be $context"
kubectl get namespace "$namespace" >/dev/null 2>&1 || die "namespace does not exist: $namespace"
simplematch_kind_namespace_is_disposable "$context" "$namespace" ||
  die 'refusing failure certification outside a lifecycle-labeled disposable namespace'

mkdir -p "$evidence_dir"
evidence_dir="$(cd -- "$evidence_dir" && pwd)"
shopt -s nullglob dotglob
existing_evidence=("$evidence_dir"/*)
shopt -u nullglob dotglob
((${#existing_evidence[@]} == 0)) || die "evidence directory must be empty: $evidence_dir"
mkdir -p \
  "$evidence_dir/baseline" \
  "$evidence_dir/submission" \
  "$evidence_dir/outage" \
  "$evidence_dir/recovery" \
  "$evidence_dir/fix" \
  "$evidence_dir/client-state" \
  "$evidence_dir/diagnostics"
evidence_initialized=true

current_stage="capture original workload configuration"
original_matching_replicas="$(workload_replicas statefulset matching)"
original_postgres_replicas="$(workload_replicas statefulset postgres)"
original_account_replicas="$(workload_replicas deployment account-service)"
original_persistence_replicas="$(workload_replicas deployment persistence)"
original_quickfix_replicas="$(workload_replicas statefulset quickfix-gateway)"
[[ "$original_matching_replicas" == 15 ]] || die "expected 15 Matching replicas, found $original_matching_replicas"
[[ "$original_postgres_replicas" == 1 ]] || die "expected one PostgreSQL replica, found $original_postgres_replicas"
(( original_account_replicas > 0 )) || die 'Account Service must be running'
(( original_persistence_replicas > 0 )) || die 'Persistence must be running'
[[ "$original_quickfix_replicas" == 1 ]] || die "expected one QuickFIX Gateway replica, found $original_quickfix_replicas"

current_stage="wait for healthy baseline workloads"
kns rollout status statefulset/matching --timeout="${timeout_seconds}s" >/dev/null
kns rollout status statefulset/postgres --timeout="${timeout_seconds}s" >/dev/null
kns rollout status deployment/account-service --timeout="${timeout_seconds}s" >/dev/null
kns rollout status deployment/persistence --timeout="${timeout_seconds}s" >/dev/null
kns rollout status statefulset/quickfix-gateway --timeout="${timeout_seconds}s" >/dev/null
kns rollout status deployment/risk-service --timeout="${timeout_seconds}s" >/dev/null
kns rollout status deployment/kafka-connect --timeout="${timeout_seconds}s" >/dev/null
kns wait --for=jsonpath='{.status.readyReplicas}'=3 statefulset/kafka --timeout="${timeout_seconds}s" >/dev/null
capture_consumer_state "$evidence_dir/baseline/consumer-state.json" || die 'cannot read baseline consumer state'
require_clean_baseline "$evidence_dir/baseline/consumer-state.json"

current_stage="prepare deterministic order input"
select_market_input
account_id="$(cat /proc/sys/kernel/random/uuid)"
cl_ord_id="FAIL-$(date -u +%Y%m%d-%H%M%S)-$$"
seed_account_limit

current_stage="prepare Gateway and external clients"
enable_gateway_operations
start_connect_port_forward
wait_outbox_connector_state RUNNING "$evidence_dir/baseline/outbox-running-status.json" ||
  die 'risk-service-outbox is not RUNNING before the test barrier'
start_fix_port_forward
start_fix_submit_client || die 'retained FIX client did not log on and reach the submission barrier'
start_gateway_port_forward
start_kafka_observation_adapter

current_stage="establish deterministic Risk outbox barrier"
pause_risk_outbox
capture_topic_offsets matching.commands "$evidence_dir/baseline/matching-commands-offsets.json" ||
  die 'cannot capture matching.commands baseline offsets'

current_stage="open Gateway from fresh system observations"
for check in 1 2 3; do
  submit_open_eligible_observation "$check" ||
    die "Gateway observation $check did not become OPEN_ELIGIBLE"
  sleep 0.2
done

open_request="$evidence_dir/baseline/gateway-open-request.json"
open_response="$evidence_dir/baseline/gateway-open-response.json"
jq -n '{actor:"local-certification", reason:"critical consumer failure certification"}' >"$open_request"
gateway_request POST /operations/open "$open_response" "$open_request" ||
  die 'Gateway operations adapter rejected open command'
jq -e '.accepted == true and .gateState == "OPEN" and (.occurredAt | type == "string")' \
  "$open_response" >/dev/null || {
  jq . "$open_response" >&2
  die 'Gateway admission did not enter OPEN state'
}

current_stage="submit FIX order immediately after Gateway open"
release_fix_submit_client
wait_fix_submit_client || die 'retained FIX submission client failed'
stop_fix_port_forward
stop_gateway_port_forward
stop_kafka_observation_adapter
kns delete pod "$kafka_observer_pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true

open_epoch_millis="$(date -u -d "$(jq -r '.occurredAt' "$open_response")" +%s%3N)"
sent_epoch_millis="$(jq -er '.sentAtEpochMs | select(type == "number")' "$evidence_dir/fix/submit.json")"
submission_delay_millis="$((sent_epoch_millis - open_epoch_millis))"
(( submission_delay_millis >= 0 && submission_delay_millis <= 2000 )) ||
  die "FIX order was sent ${submission_delay_millis}ms after Gateway open; expected at most 2000ms"
jq -n \
  --argjson gatewayOpenEpochMs "$open_epoch_millis" \
  --argjson fixSentAtEpochMs "$sent_epoch_millis" \
  --argjson delayMillis "$submission_delay_millis" \
  '{gatewayOpenEpochMs:$gatewayOpenEpochMs,fixSentAtEpochMs:$fixSentAtEpochMs,delayMillis:$delayMillis}' \
  >"$evidence_dir/submission/gateway-open-to-fix-send.json"

current_stage="verify durable Risk admission before fault injection"
capture_risk_admission "$evidence_dir/submission/risk-admission.json"
command_id="$(jq -r '.commandId' "$evidence_dir/submission/risk-admission.json")"
order_id="$(jq -r '.orderId' "$evidence_dir/submission/risk-admission.json")"
partition="$(jq -r '.routingPartition' "$evidence_dir/submission/risk-admission.json")"
require_matching_command_held "$evidence_dir/baseline/matching-commands-offsets.json" "$partition"

current_stage="stop Matching before publishing accepted command"
scale_statefulset matching 0
release_matching_command "$evidence_dir/baseline/matching-commands-offsets.json" "$partition"
capture_topic_offsets matching.events "$evidence_dir/baseline/matching-events-offsets.json" ||
  die 'cannot capture pre-outage matching.events offsets'
start_event_offset="$(offset_for_partition "$evidence_dir/baseline/matching-events-offsets.json" "$partition")"

current_stage="stop PostgreSQL and critical consumers"
scale_deployment account-service 0
scale_deployment persistence 0
scale_statefulset quickfix-gateway 0
scale_statefulset postgres 0

current_stage="observe Matching publication during downstream outage"
create_observer_pod
scale_statefulset matching "$original_matching_replicas"
run_event_observer "$partition" "$start_event_offset" "$command_id" "$order_id"
[[ "$(workload_replicas statefulset postgres)" == 0 ]] || die 'PostgreSQL was restored before event observation'
[[ "$(kns get pods -l app.kubernetes.io/name=postgres -o json | jq '.items | length')" == 0 ]] ||
  die 'a PostgreSQL Pod existed during event observation'
[[ "$(workload_replicas deployment account-service)" == 0 ]] || die 'Account Service was running during outage observation'
[[ "$(workload_replicas deployment persistence)" == 0 ]] || die 'Persistence was running during outage observation'
[[ "$(workload_replicas statefulset quickfix-gateway)" == 0 ]] || die 'QuickFIX Gateway was running during outage observation'
event_offset="$(jq -er '.offset' "$evidence_dir/outage/matching-event-observation.json")"
event_id="$(jq -er '.eventId' "$evidence_dir/outage/matching-event-observation.json")"
[[ "$event_offset" =~ ^[0-9]+$ ]] || die 'observed Matching Event offset is invalid'
[[ "$event_id" =~ ^[0-9a-f]{64}$ ]] || die 'observed Matching Event identity is invalid'

current_stage="restore PostgreSQL and critical consumers"
scale_statefulset postgres "$original_postgres_replicas"
scale_deployment account-service "$original_account_replicas"
scale_deployment persistence "$original_persistence_replicas"
scale_statefulset quickfix-gateway "$original_quickfix_replicas"
wait_consumers_through "$partition" "$event_offset"
capture_consumer_state "$evidence_dir/recovery/consumer-state.json" || die 'cannot read recovered consumer state'
jq -e '.persistenceQuarantines == 0 and .accountQuarantines == 0 and .quickfixQuarantines == 0' \
  "$evidence_dir/recovery/consumer-state.json" >/dev/null ||
  die 'a critical consumer remained quarantined after recovery'
require_exact_event_once "$event_id"
wait_fix_intent_status PENDING "$evidence_dir/recovery/fix-intent-pending.json"
[[ "$(jq -r '.eventId' "$evidence_dir/recovery/fix-intent-pending.json")" == "$event_id" ]] ||
  die 'pending QuickFIX delivery intent belongs to a different Matching Event'

current_stage="verify retained FIX delivery and explicit resend"
start_fix_port_forward
run_fix_phase receive-resend "$evidence_dir/fix/receive-resend.json"
stop_fix_port_forward
wait_fix_intent_status SENT "$evidence_dir/recovery/fix-intent-sent.json"
expected_seq="$(jq -er '.msgSeqNum' "$evidence_dir/fix/receive-resend.json")"
expected_exec_id="$(jq -er '.execId' "$evidence_dir/fix/receive-resend.json")"
[[ "$expected_seq" =~ ^[1-9][0-9]*$ ]] || die 'retained FIX message sequence is invalid'
[[ -n "$expected_exec_id" ]] || die 'retained FIX execution identity is missing'
jq -e '.possDup == true' "$evidence_dir/fix/receive-resend.json" >/dev/null ||
  die 'explicit FIX retransmission did not set PossDupFlag'

current_stage="verify retained FIX resend across Gateway restart"
quickfix_uid_before="$(quickfix_pod_uid)"
kns rollout restart statefulset/quickfix-gateway >/dev/null
kns rollout status statefulset/quickfix-gateway --timeout="${timeout_seconds}s" >/dev/null
quickfix_uid_after="$(quickfix_pod_uid)"
[[ "$quickfix_uid_after" != "$quickfix_uid_before" ]] || die 'QuickFIX Gateway Pod identity did not change across restart'
start_fix_port_forward
run_fix_phase resend-only "$evidence_dir/fix/resend-after-gateway-restart.json" \
  SIMPLEMATCH_RETAINED_FIX_EXPECTED_MSG_SEQ_NUM="$expected_seq" \
  SIMPLEMATCH_RETAINED_FIX_EXPECTED_EXEC_ID="$expected_exec_id"
stop_fix_port_forward
jq -e --arg exec_id "$expected_exec_id" --argjson sequence "$expected_seq" '
  .execId == $exec_id and .msgSeqNum == $sequence and .possDup == true
' "$evidence_dir/fix/resend-after-gateway-restart.json" >/dev/null ||
  die 'Gateway restart changed FIX retransmission identity or sequence semantics'

current_stage="verify final durable consumer state"
capture_consumer_state "$evidence_dir/recovery/final-consumer-state.json" || die 'cannot read final consumer state'
jq -e '
  .persistenceQuarantines == 0
  and .accountQuarantines == 0
  and .quickfixQuarantines == 0
  and .quickfixPendingIntents == 0
' "$evidence_dir/recovery/final-consumer-state.json" >/dev/null ||
  die 'final critical-consumer state is not healthy'

current_stage="completed"
jq -n \
  --arg status PASS \
  --arg namespace "$namespace" \
  --arg commandId "$command_id" \
  --arg orderId "$order_id" \
  --arg eventId "$event_id" \
  --arg execId "$expected_exec_id" \
  --argjson partition "$partition" \
  --argjson eventOffset "$event_offset" \
  --argjson fixMsgSeqNum "$expected_seq" \
  --argjson gatewayOpenToFixSendMillis "$submission_delay_millis" \
  '{
    status:$status,
    namespace:$namespace,
    commandId:$commandId,
    orderId:$orderId,
    eventId:$eventId,
    partition:$partition,
    eventOffset:$eventOffset,
    execId:$execId,
    fixMsgSeqNum:$fixMsgSeqNum,
    gatewayOpenToFixSendMillis:$gatewayOpenToFixSendMillis,
    proven:[
      "Matching, Risk, Kafka, and critical consumers were observed healthy immediately before Gateway open",
      "the external FIX client was already logged on before the freshness-sensitive admission window",
      "the FIX order was sent within two seconds of Gateway open while the stale-observation monitor remained enabled",
      "Risk committed the accepted order while its outbox connector was intentionally paused",
      "matching.commands did not advance before the Matching failure boundary",
      "the accepted command reached Kafka while Matching was stopped",
      "PostgreSQL and all three critical consumers were unavailable",
      "Matching published the exact command/order event to Kafka during the outage",
      "Kafka retained the event until PostgreSQL and consumers recovered",
      "all three critical consumers processed the exact event once",
      "offline FIX delivery remained durably PENDING",
      "explicit FIX ResendRequest preserved MsgSeqNum and stable ExecID",
      "Gateway restart preserved the same FIX retransmission identity",
      "no critical consumer quarantine or pending FIX intent remained"
    ],
    outOfScope:["exactly-once network delivery"]
  }' >"$evidence_dir/verdict.json"

printf 'Critical consumer failure certification passed: %s\n' "$evidence_dir/verdict.json"

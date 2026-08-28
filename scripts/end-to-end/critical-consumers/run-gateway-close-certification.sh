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
# shellcheck source=scripts/end-to-end/critical-consumers/lib/gateway-close-verification.sh
source "$script_dir/lib/gateway-close-verification.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="kind-$cluster_name"
namespace=""
retained_evidence_dir=""
evidence_dir=""
timeout_seconds="${SIMPLEMATCH_GATEWAY_CLOSE_CERTIFICATION_TIMEOUT_SECONDS:-180}"

kafka_observer_pod="critical-consumer-kafka-observer"
kafka_observer_manifest="$repo_root/deploy/k8s/verification/critical-consumer-kafka-observer-pod.yaml"
kafka_observer_port_forward_pid=""
kafka_observer_port=""
kafka_observer_created=false
fix_port_forward_pid=""
fix_port=""
fix_submit_pid=""
fix_ready_file=""
fix_release_file=""
fix_submit_log=""
gateway_port_forward_pid=""
gateway_port=""
gateway_operator_token=""
gateway_env_modified=false
restoration_failed=false
evidence_initialized=false
current_stage="preflight"
failure_reason=""
pending_pass_verdict=""
order_id=""
terminal_event_id=""

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/end-to-end/critical-consumers/run-gateway-close-certification.sh \
    --namespace NAME \
    --retained-evidence-dir PATH \
    --evidence-dir PATH \
    [--timeout-seconds N]

Runs the terminal Gateway trading-session close capability on one retained
production-like namespace. The retained evidence directory is an explicit input
so source revision, namespace, and verifier image provenance cannot be resolved
from an unrelated run.

The runner opens Gateway admission from three fresh observations, creates one
real resting order, invokes close-day, and proves one Close Barrier per Matching
partition, durable Matching closure, exact terminal-event processing by all
critical consumers, and expiration of the selected resting order.

Run this capability last for a retained trading session. A successful close is
terminal for that Gateway process and Matching session.
EOF_USAGE
}

fail() {
  failure_reason="$*"
  printf 'Gateway close certification: %s\n' "$failure_reason" >&2
  return 1
}

# Shared critical-consumer helpers require a terminal fail-closed callback.
die() {
  failure_reason="$*"
  printf 'Gateway close certification: %s\n' "$failure_reason" >&2
  exit 1
}

submit_open_eligible_observation() {
  local check="$1"
  local observation="$evidence_dir/baseline/gateway-observation-$check.json"
  local gateway_attempt response

  # The collector owns source-stability retries. This outer retry exists only
  # for the narrow race where a valid payload becomes stale during HTTP submit.
  for gateway_attempt in 1 2; do
    response="$evidence_dir/baseline/gateway-observation-${check}-gateway-attempt-${gateway_attempt}.json"
    capture_gateway_observation "$check" "$observation" || return 1
    gateway_request POST /operations/observations "$response" "$observation" || return 1
    if jq -e '.readiness == "OPEN_ELIGIBLE"' "$response" >/dev/null; then
      cp "$response" "$evidence_dir/open/observation-$check.json" || return 1
      return 0
    fi
    gateway_response_is_retryable_stale "$response" || return 1
  done
  return 1
}

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
  delete_kafka_observer_pod || restoration_failed=true
  restore_gateway_environment

  if [[ "$restoration_failed" == true ]]; then
    status=1
    [[ -n "$failure_reason" ]] || failure_reason='environment restoration failed'
  fi

  if (( status != 0 )); then
    [[ -z "$pending_pass_verdict" ]] || rm -f "$pending_pass_verdict"
    write_failure_verdict "$status"
  elif [[ -n "$pending_pass_verdict" ]]; then
    if mv "$pending_pass_verdict" "$evidence_dir/verdict.json"; then
      printf 'Gateway close certification passed: %s\n' "$evidence_dir/verdict.json"
    else
      status=1
      failure_reason='failed to publish successful certification verdict'
      rm -f "$pending_pass_verdict"
      write_failure_verdict "$status"
    fi
  fi
  exit "$status"
}

on_error() {
  local status="$1"
  [[ -n "$failure_reason" ]] ||
    failure_reason="unexpected command failure during $current_stage"
  return "$status"
}

parse_args() {
  while (($# > 0)); do
    case "$1" in
      --namespace)
        namespace="${2:?--namespace requires a value}"
        shift 2
        ;;
      --retained-evidence-dir)
        retained_evidence_dir="${2:?--retained-evidence-dir requires a value}"
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
        return 2
        ;;
      *)
        usage >&2
        fail "unknown option: $1"
        return 1
        ;;
    esac
  done
}

validate_args() {
  [[ -n "$namespace" ]] || {
    fail '--namespace is required'
    return 1
  }
  [[ -n "$retained_evidence_dir" ]] || {
    fail '--retained-evidence-dir is required'
    return 1
  }
  [[ -n "$evidence_dir" ]] || {
    fail '--evidence-dir is required'
    return 1
  }
  [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || {
    fail '--timeout-seconds must be a positive integer'
    return 1
  }
  (( timeout_seconds <= 300 )) || {
    fail '--timeout-seconds must not exceed 300'
    return 1
  }
}

validate_tools() {
  local tool
  for tool in kubectl jq curl awk sed grep date seq sleep tr cp mv git mkdir cat; do
    command -v "$tool" >/dev/null 2>&1 || {
      fail "$tool is required"
      return 1
    }
  done
  [[ -x "$repo_root/gradlew" ]] || {
    fail 'Gradle wrapper is missing'
    return 1
  }
}

initialize_evidence() {
  mkdir -p "$evidence_dir" || {
    fail "cannot create evidence directory: $evidence_dir"
    return 1
  }
  evidence_dir="$(cd -- "$evidence_dir" && pwd)" || {
    fail "cannot resolve evidence directory: $evidence_dir"
    return 1
  }

  shopt -s nullglob dotglob
  local existing_evidence=("$evidence_dir"/*)
  shopt -u nullglob dotglob
  ((${#existing_evidence[@]} == 0)) || {
    fail "evidence directory must be empty: $evidence_dir"
    return 1
  }

  mkdir -p \
    "$evidence_dir/baseline" \
    "$evidence_dir/open" \
    "$evidence_dir/submission" \
    "$evidence_dir/close" \
    "$evidence_dir/fix" \
    "$evidence_dir/client-state" \
    "$evidence_dir/diagnostics" || {
    fail 'cannot initialize certification evidence directories'
    return 1
  }
  evidence_initialized=true
  trap 'on_error $?' ERR
  trap cleanup EXIT
}

validate_retained_run() {
  current_stage="validate retained production-like provenance"

  [[ -f "$kafka_observer_manifest" ]] || {
    fail "Kafka observer Pod manifest is missing: $kafka_observer_manifest"
    return 1
  }
  [[ -r /proc/sys/kernel/random/uuid ]] || {
    fail '/proc/sys/kernel/random/uuid is required'
    return 1
  }
  [[ -d "$retained_evidence_dir" ]] || {
    fail "retained evidence directory does not exist: $retained_evidence_dir"
    return 1
  }
  retained_evidence_dir="$(cd -- "$retained_evidence_dir" && pwd)" || {
    fail "cannot resolve retained evidence directory: $retained_evidence_dir"
    return 1
  }

  [[ "$(kubectl config current-context)" == "$context" ]] || {
    fail "current Kubernetes context must be $context"
    return 1
  }
  kubectl get namespace "$namespace" >/dev/null 2>&1 || {
    fail "namespace does not exist: $namespace"
    return 1
  }
  simplematch_kind_namespace_is_disposable "$context" "$namespace" || {
    fail 'refusing Gateway close certification outside a lifecycle-labeled disposable namespace'
    return 1
  }

  local verifier_image_reference
  verifier_image_reference="$(
    simplematch_certification_verifier_image \
      "$repo_root" "$namespace" "$retained_evidence_dir"
  )" || {
    fail 'retained production-like source or verifier image provenance is not valid'
    return 1
  }
  printf '%s\n' "$verifier_image_reference" \
    >"$evidence_dir/baseline/verifier-image-reference" || {
    fail 'cannot record verifier image reference in certification evidence'
    return 1
  }
}

wait_for_rollout() {
  local resource="$1"
  kns rollout status "$resource" --timeout="${timeout_seconds}s" >/dev/null || {
    fail "workload did not become Ready: $resource"
    return 1
  }
}

wait_for_clean_baseline() {
  current_stage="wait for healthy baseline workloads"

  local resource
  for resource in \
    statefulset/matching \
    statefulset/postgres \
    deployment/account-service \
    deployment/persistence \
    statefulset/quickfix-gateway \
    deployment/risk-service \
    deployment/kafka-connect; do
    wait_for_rollout "$resource" || return 1
  done
  kns wait --for=jsonpath='{.status.readyReplicas}'=3 statefulset/kafka \
    --timeout="${timeout_seconds}s" >/dev/null || {
    fail 'Kafka StatefulSet did not reach three Ready replicas'
    return 1
  }

  capture_consumer_state "$evidence_dir/baseline/consumer-state.json" || {
    fail 'cannot read baseline critical-consumer state'
    return 1
  }
  require_clean_baseline "$evidence_dir/baseline/consumer-state.json" || return 1
}

prepare_certification_clients() {
  current_stage="prepare one resting order"
  select_market_input || {
    fail 'cannot select a market input for the certification order'
    return 1
  }
  account_id="$(cat /proc/sys/kernel/random/uuid)" || {
    fail 'cannot generate certification account identity'
    return 1
  }
  cl_ord_id="CLOSE-$(date -u +%Y%m%d-%H%M%S)-$$" || {
    fail 'cannot generate certification client order identity'
    return 1
  }
  seed_account_limit || {
    fail 'cannot seed the certification account limit'
    return 1
  }

  current_stage="prepare Gateway, FIX, and Kafka clients"
  enable_gateway_operations || return 1
  start_fix_port_forward || return 1
  start_fix_submit_client || {
    fail 'prepared FIX client did not log on and reach the submission barrier'
    return 1
  }
  start_gateway_port_forward || return 1
  start_kafka_observation_adapter "$retained_evidence_dir" || return 1
}

open_gateway() {
  current_stage="open Gateway from fresh system observations"

  local check
  for check in 1 2 3; do
    submit_open_eligible_observation "$check" || {
      fail "Gateway observation $check did not become OPEN_ELIGIBLE"
      return 1
    }
    sleep 0.2
  done

  local open_request="$evidence_dir/open/request.json"
  local open_response="$evidence_dir/open/response.json"
  jq -n '{actor:"local-certification", reason:"Gateway close certification"}' \
    >"$open_request" || {
    fail 'cannot write Gateway open request evidence'
    return 1
  }
  gateway_request POST /operations/open "$open_response" "$open_request" || {
    fail 'Gateway operations adapter rejected open command'
    return 1
  }
  jq -e '.accepted == true and .gateState == "OPEN"' "$open_response" >/dev/null || {
    fail 'Gateway admission did not enter OPEN state'
    return 1
  }
}

submit_resting_order() {
  current_stage="submit and observe one resting order"

  require_live_fix_trading_day "$trading_day" || return 1
  release_fix_submit_client || {
    fail 'cannot release the prepared FIX submission client'
    return 1
  }
  wait_fix_submit_client || {
    fail 'prepared FIX submission client failed'
    return 1
  }
  stop_fix_port_forward
  capture_risk_admission "$evidence_dir/submission/risk-admission.json" || return 1
  order_id="$(jq -er '.orderId' "$evidence_dir/submission/risk-admission.json")" || {
    fail 'Risk admission evidence does not contain an order identity'
    return 1
  }
  wait_order_projection_status \
    "$order_id" RESTING "$evidence_dir/submission/resting-order.json" || {
    fail 'accepted order did not become RESTING before session close'
    return 1
  }
}

close_gateway() {
  current_stage="capture pre-close Kafka positions"
  capture_kafka_matching_commands_end_positions \
    "$evidence_dir/close/commands-before.json" || {
    fail 'cannot capture pre-close matching.commands positions'
    return 1
  }

  current_stage="close Gateway admission"
  local close_request="$evidence_dir/close/request.json"
  local close_response="$evidence_dir/close/response.json"
  jq -n '{actor:"local-certification", reason:"terminal close certification"}' \
    >"$close_request" || {
    fail 'cannot write Gateway close request evidence'
    return 1
  }
  gateway_request POST /operations/close-day "$close_response" "$close_request" || {
    fail 'Gateway operations adapter rejected close-day command'
    return 1
  }
  jq -e '
    .accepted == true
    and .gateState == "CLOSED"
    and .reason == "MARKET_CLOSED"
  ' "$close_response" >/dev/null || {
    fail 'Gateway did not enter CLOSED after close-day'
    return 1
  }
}

verify_close_barriers_and_matching() {
  current_stage="verify all Close Barriers are published exactly once"
  wait_close_barriers_published \
    "$evidence_dir/close/commands-before.json" \
    "$evidence_dir/close/commands-after.json" || {
    fail 'matching.commands did not advance exactly once on every partition'
    return 1
  }
  verify_close_barrier_payloads \
    "$evidence_dir/close/commands-before.json" \
    "$evidence_dir/close/commands-after.json" \
    "$evidence_dir/close/close-barriers.json" || {
    fail 'matching.commands range does not contain the expected Close Barrier identities'
    return 1
  }

  current_stage="verify Matching commits and closes every owner"
  wait_matching_committed_to_close \
    "$evidence_dir/close/commands-after.json" \
    "$evidence_dir/close/matching-committed.json" || {
    fail 'one or more Matching owners did not commit through its Close Barrier'
    return 1
  }
  wait_matching_closed "$evidence_dir/close/matching-closed.json" || {
    fail 'one or more Matching owners did not reach CLOSED with drained runtime queues'
    return 1
  }
}

verify_terminal_event_and_consumers() {
  current_stage="verify resting order expiration"
  wait_order_projection_status \
    "$order_id" EXPIRED "$evidence_dir/close/expired-order.json" || {
    fail 'resting order did not become EXPIRED after Close Barrier processing'
    return 1
  }
  terminal_event_id="$(
    jq -er '.lastEventId | select(type == "string" and test("^[0-9a-f]+$"))' \
      "$evidence_dir/close/expired-order.json"
  )" || {
    fail 'expired order projection does not identify its terminal Matching Event'
    return 1
  }

  current_stage="verify critical-consumer drain"
  capture_kafka_matching_events_end_positions "$evidence_dir/close/events-after.json" || {
    fail 'cannot capture post-close matching.events positions'
    return 1
  }
  wait_critical_consumers_to_close \
    "$evidence_dir/close/events-after.json" \
    "$evidence_dir/close/consumer-state.json" || {
    fail 'critical consumers did not drain through the close workflow event positions'
    return 1
  }
  require_exact_event_once "$terminal_event_id" || return 1
}

stage_success_verdict() {
  current_stage="stage successful certification verdict"
  pending_pass_verdict="$evidence_dir/verdict.pending.json"

  jq -n \
    --arg status PASS \
    --arg namespace "$namespace" \
    --arg retainedEvidenceDir "$retained_evidence_dir" \
    --arg tradingSessionId "$trading_session_id" \
    --arg orderId "$order_id" \
    --arg terminalEventId "$terminal_event_id" \
    --slurpfile closeResponse "$evidence_dir/close/response.json" \
    --slurpfile commandsBefore "$evidence_dir/close/commands-before.json" \
    --slurpfile commandsAfter "$evidence_dir/close/commands-after.json" \
    --slurpfile closeBarriers "$evidence_dir/close/close-barriers.json" \
    --slurpfile matchingClosed "$evidence_dir/close/matching-closed.json" \
    --slurpfile consumers "$evidence_dir/close/consumer-state.json" \
    --slurpfile expiredOrder "$evidence_dir/close/expired-order.json" '
      {
        status:$status,
        namespace:$namespace,
        retainedEvidenceDir:$retainedEvidenceDir,
        tradingSessionId:$tradingSessionId,
        orderId:$orderId,
        terminalEventId:$terminalEventId,
        gatewayClose:$closeResponse[0],
        commandPositionsBefore:$commandsBefore[0],
        commandPositionsAfter:$commandsAfter[0],
        closeBarriers:$closeBarriers[0],
        matchingClosed:$matchingClosed[0],
        criticalConsumerState:$consumers[0],
        expiredOrder:$expiredOrder[0],
        proven:[
          "Gateway admission entered CLOSED before downstream drain was declared complete",
          "matching.commands advanced exactly once on partitions 0 through 14",
          "all 15 matching.commands payloads carried the expected Close Barrier and daily identity",
          "all 15 Matching consumers committed through their Close Barrier",
          "all 15 Matching runtimes reported CLOSED with no pending input or publication",
          "the pre-close resting order became EXPIRED",
          "all three critical consumers caught up through the post-close matching.events log end",
          "the selected order terminal Matching Event was processed exactly once by every critical consumer",
          "no critical-consumer quarantine history was created during the close workflow"
        ],
        outOfScope:[
          "Issue #160 production live-observation adapters",
          "external production promotion",
          "exactly-once network delivery to a disconnected FIX client"
        ]
      }
    ' >"$pending_pass_verdict"
}

main() {
  local parse_status=0
  parse_args "$@" || parse_status="$?"
  if (( parse_status == 2 )); then
    return 0
  fi
  (( parse_status == 0 )) || return "$parse_status"

  validate_args || return 1
  validate_tools || return 1
  initialize_evidence || return 1
  validate_retained_run || return 1
  wait_for_clean_baseline || return 1
  prepare_certification_clients || return 1
  open_gateway || return 1
  submit_resting_order || return 1
  close_gateway || return 1
  verify_close_barriers_and_matching || return 1
  verify_terminal_event_and_consumers || return 1
  stage_success_verdict
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi

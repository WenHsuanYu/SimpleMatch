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
timeout_seconds="${SIMPLEMATCH_GATEWAY_CLOSE_CERTIFICATION_TIMEOUT_SECONDS:-180}"

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
gateway_port_forward_pid=""
gateway_port=""
gateway_operator_token=""
gateway_env_modified=false
restoration_failed=false
evidence_initialized=false
current_stage="preflight"
failure_reason=""
pending_pass_verdict=""

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/end-to-end/critical-consumers/run-gateway-close-certification.sh \
    --namespace NAME \
    --evidence-dir PATH \
    [--timeout-seconds N]

Runs the terminal Gateway trading-session close capability on an existing
production-like retained namespace. The runner opens Gateway admission from
fresh observations, creates one real resting order, invokes close-day, and
proves that all 15 Close Barriers are published and committed, all Matching
owners reach CLOSED, the critical consumers catch up, and the resting order
becomes EXPIRED.

Run this capability last for a retained trading session. A successful close is
terminal for that Gateway process and Matching session.
EOF_USAGE
}

fail() {
  failure_reason="$*"
  printf 'Gateway close certification: %s\n' "$failure_reason" >&2
  return 1
}

# Shared critical-consumer helpers use die() as their fail-closed callback.
die() {
  fail "$*"
}

close_barriers_advanced_exactly_once() {
  local before="$1"
  local after="$2"
  jq -e -n \
    --slurpfile before "$before" \
    --slurpfile after "$after" '
      ($before[0].partitions) as $beforeRows
      | ($after[0].partitions) as $afterRows
      | ($beforeRows | length) == 15
      and ($afterRows | length) == 15
      and ([range(0; 15) as $partition
        | {
            before:($beforeRows[]
              | select(.partition == $partition)
              | .offset),
            after:($afterRows[]
              | select(.partition == $partition)
              | .offset)
          }
      ] | length) == 15
      and all(
        [range(0; 15) as $partition
          | {
              before:($beforeRows[]
                | select(.partition == $partition)
                | .offset),
              after:($afterRows[]
                | select(.partition == $partition)
                | .offset)
            }
        ][];
        .after == (.before + 1)
      )
    ' >/dev/null
}

matching_committed_covers_commands() {
  local commands="$1"
  local committed="$2"
  jq -e -n \
    --slurpfile commands "$commands" \
    --slurpfile committed "$committed" '
      ($commands[0].partitions) as $endRows
      | ($committed[0].partitions) as $committedRows
      | ($endRows | length) == 15
      and ($committedRows | length) == 15
      and all(
        [range(0; 15) as $partition
          | {
              end:($endRows[]
                | select(.partition == $partition)
                | .offset),
              committed:($committedRows[]
                | select(.partition == $partition)
                | .committedOffset)
            }
        ][];
        .committed >= .end
      )
    ' >/dev/null
}

critical_consumers_cover_events() {
  local events="$1"
  local consumers="$2"
  jq -e -n \
    --slurpfile events "$events" \
    --slurpfile consumers "$consumers" '
      def covers($rows; $partition; $endOffset):
        if $endOffset == 0 then true
        else
          any(
            $rows[]?;
            .partition_id == $partition
            and .last_processed_offset >= ($endOffset - 1)
          )
        end;
      ($events[0].partitions) as $endRows
      | ($consumers[0]) as $state
      | ($endRows | length) == 15
      and all(
        $endRows[];
        . as $end
        | covers($state.persistenceProgress; $end.partition; $end.offset)
        and covers($state.accountProgress; $end.partition; $end.offset)
        and covers($state.quickfixProgress; $end.partition; $end.offset)
      )
      and $state.persistenceQuarantines == 0
      and $state.accountQuarantines == 0
      and $state.quickfixQuarantines == 0
    ' >/dev/null
}

capture_order_projection() {
  local order_id="$1"
  local destination="$2"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1

  local projection
  projection="$(
    kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
      -v ON_ERROR_STOP=1 -c "
        SELECT json_build_object(
          'orderId', order_id::text,
          'status', status,
          'cumulativeQuantityShares', cumulative_quantity_shares,
          'leavesQuantityShares', leaves_quantity_shares,
          'lastEventId', encode(last_event_id, 'hex')
        )::text
        FROM persistence.matching_order_projections
        WHERE order_id = '$order_id';
      " 2>/dev/null
  )" || return 1
  [[ -n "$projection" ]] || return 1
  printf '%s\n' "$projection" | jq . >"$destination"
}

wait_order_projection_status() {
  local order_id="$1"
  local expected="$2"
  local destination="$3"
  local attempt
  for attempt in $(seq 1 "$timeout_seconds"); do
    if capture_order_projection "$order_id" "$destination" \
        && jq -e --arg expected "$expected" '.status == $expected' \
          "$destination" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_close_barriers_published() {
  local before="$1"
  local after="$2"
  local attempt
  for attempt in $(seq 1 "$timeout_seconds"); do
    if capture_topic_offsets matching.commands "$after" \
        && close_barriers_advanced_exactly_once "$before" "$after"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_matching_committed_to_close() {
  local commands="$1"
  local committed="$2"
  local attempt
  for attempt in $(seq 1 "$timeout_seconds"); do
    if capture_matching_committed_offsets "$committed" \
        && matching_committed_covers_commands "$commands" "$committed"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

capture_matching_closed_state() {
  local destination="$1"
  local temporary="${destination}.next"
  : >"$temporary"

  local partition
  for partition in $(seq 0 14); do
    local metrics
    metrics="$(
      kns exec "matching-$partition" -c matching -- \
        cat /var/lib/simplematch/matching/runtime-metrics.json 2>/dev/null
    )" || return 1
    jq -e \
      --argjson partition "$partition" '
        select(
          .schema_version == 1
          and .partition_state == "CLOSED"
          and .pending_inputs == 0
          and .pending_publications == 0
        )
        | {partitionId:$partition, runtime:.}
      ' <<<"$metrics" >>"$temporary" || return 1
  done

  jq -s 'sort_by(.partitionId)' "$temporary" >"$destination" || return 1
  rm -f "$temporary"
  jq -e '
    length == 15
    and ([.[].partitionId] == [range(0; 15)])
    and all(.[];
      .runtime.partition_state == "CLOSED"
      and .runtime.pending_inputs == 0
      and .runtime.pending_publications == 0)
  ' "$destination" >/dev/null
}

wait_matching_closed() {
  local destination="$1"
  local attempt
  for attempt in $(seq 1 "$timeout_seconds"); do
    if capture_matching_closed_state "$destination"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_critical_consumers_to_close() {
  local events="$1"
  local consumers="$2"
  local attempt
  for attempt in $(seq 1 "$timeout_seconds"); do
    if capture_consumer_state "$consumers" \
        && critical_consumers_cover_events "$events" "$consumers"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

submit_open_eligible_observation() {
  local check="$1"
  local observation="$evidence_dir/open/gateway-observation-$check.json"
  local attempt response

  for attempt in 1 2 3; do
    response="$evidence_dir/open/gateway-observation-${check}-attempt-${attempt}.json"
    capture_gateway_observation "$check" "$observation" || return 1
    gateway_request POST /operations/observations "$response" "$observation" || return 1
    if jq -e '.readiness == "OPEN_ELIGIBLE"' "$response" >/dev/null; then
      return 0
    fi
    gateway_response_is_retryable_stale "$response" || return 1
    sleep 0.2
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
  kns delete pod "$kafka_observer_pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true
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
  [[ -n "$failure_reason" ]] || failure_reason="unexpected command failure during $current_stage"
  return "$status"
}

main() {
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
        return 0
        ;;
      *)
        usage >&2
        fail "unknown option: $1"
        return 1
        ;;
    esac
  done

  [[ -n "$namespace" ]] || { fail '--namespace is required'; return 1; }
  [[ -n "$evidence_dir" ]] || { fail '--evidence-dir is required'; return 1; }
  [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || {
    fail '--timeout-seconds must be a positive integer'
    return 1
  }
  (( timeout_seconds <= 300 )) || {
    fail '--timeout-seconds must not exceed 300'
    return 1
  }

  local tool
  for tool in kubectl jq curl awk sed grep date seq sleep tr cp mv; do
    command -v "$tool" >/dev/null 2>&1 || {
      fail "$tool is required"
      return 1
    }
  done
  [[ -x "$repo_root/gradlew" ]] || {
    fail 'Gradle wrapper is missing'
    return 1
  }
  [[ -f "$kafka_observer_manifest" ]] || {
    fail "Kafka observer Pod manifest is missing: $kafka_observer_manifest"
    return 1
  }
  [[ -r /proc/sys/kernel/random/uuid ]] || {
    fail '/proc/sys/kernel/random/uuid is required'
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

  mkdir -p "$evidence_dir"
  evidence_dir="$(cd -- "$evidence_dir" && pwd)"
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
    "$evidence_dir/client-state"
  evidence_initialized=true

  trap 'on_error $?' ERR
  trap cleanup EXIT

  current_stage="wait for healthy baseline workloads"
  kns rollout status statefulset/matching --timeout="${timeout_seconds}s" >/dev/null
  kns rollout status statefulset/postgres --timeout="${timeout_seconds}s" >/dev/null
  kns rollout status deployment/account-service --timeout="${timeout_seconds}s" >/dev/null
  kns rollout status deployment/persistence --timeout="${timeout_seconds}s" >/dev/null
  kns rollout status statefulset/quickfix-gateway --timeout="${timeout_seconds}s" >/dev/null
  kns rollout status deployment/risk-service --timeout="${timeout_seconds}s" >/dev/null
  kns rollout status deployment/kafka-connect --timeout="${timeout_seconds}s" >/dev/null
  kns wait --for=jsonpath='{.status.readyReplicas}'=3 statefulset/kafka \
    --timeout="${timeout_seconds}s" >/dev/null
  capture_consumer_state "$evidence_dir/baseline/consumer-state.json" || {
    fail 'cannot read baseline critical-consumer state'
    return 1
  }
  require_clean_baseline "$evidence_dir/baseline/consumer-state.json" || return 1

  current_stage="prepare one resting order"
  select_market_input
  account_id="$(cat /proc/sys/kernel/random/uuid)"
  cl_ord_id="CLOSE-$(date -u +%Y%m%d-%H%M%S)-$$"
  seed_account_limit

  current_stage="prepare Gateway and FIX clients"
  enable_gateway_operations
  start_fix_port_forward
  start_fix_submit_client || {
    fail 'prepared FIX client did not log on and reach the submission barrier'
    return 1
  }
  start_gateway_port_forward
  start_kafka_observation_adapter

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
  jq -n '{actor:"local-certification", reason:"Gateway close certification"}' >"$open_request"
  gateway_request POST /operations/open "$open_response" "$open_request" || {
    fail 'Gateway operations adapter rejected open command'
    return 1
  }
  jq -e '.accepted == true and .gateState == "OPEN"' "$open_response" >/dev/null || {
    fail 'Gateway admission did not enter OPEN state'
    return 1
  }

  current_stage="submit and observe one resting order"
  require_live_fix_trading_day "$trading_day"
  release_fix_submit_client
  wait_fix_submit_client || {
    fail 'prepared FIX submission client failed'
    return 1
  }
  stop_fix_port_forward
  capture_risk_admission "$evidence_dir/submission/risk-admission.json"
  local order_id
  order_id="$(jq -er '.orderId' "$evidence_dir/submission/risk-admission.json")"
  wait_order_projection_status \
    "$order_id" RESTING "$evidence_dir/submission/resting-order.json" || {
    fail 'accepted order did not become RESTING before session close'
    return 1
  }

  current_stage="capture pre-close Kafka positions"
  capture_topic_offsets matching.commands "$evidence_dir/close/commands-before.json" || {
    fail 'cannot capture pre-close matching.commands positions'
    return 1
  }
  capture_topic_offsets matching.events "$evidence_dir/close/events-before.json" || {
    fail 'cannot capture pre-close matching.events positions'
    return 1
  }

  current_stage="close Gateway admission"
  local close_request="$evidence_dir/close/request.json"
  local close_response="$evidence_dir/close/response.json"
  jq -n '{actor:"local-certification", reason:"terminal close certification"}' >"$close_request"
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

  current_stage="verify all Close Barriers are published exactly once"
  wait_close_barriers_published \
    "$evidence_dir/close/commands-before.json" \
    "$evidence_dir/close/commands-after.json" || {
    fail 'matching.commands did not advance exactly once on every partition'
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

  current_stage="verify terminal Matching event and critical-consumer drain"
  local event_attempt
  for event_attempt in $(seq 1 "$timeout_seconds"); do
    capture_topic_offsets matching.events "$evidence_dir/close/events-after.json" || {
      sleep 1
      continue
    }
    if jq -e -n \
        --slurpfile before "$evidence_dir/close/events-before.json" \
        --slurpfile after "$evidence_dir/close/events-after.json" '
          ($before[0].partitions) as $beforeRows
          | ($after[0].partitions) as $afterRows
          | any(
              $afterRows[];
              . as $after
              | any(
                  $beforeRows[];
                  .partition == $after.partition and .offset < $after.offset
                )
            )
        ' >/dev/null; then
      break
    fi
    sleep 1
  done
  if ! jq -e -n \
      --slurpfile before "$evidence_dir/close/events-before.json" \
      --slurpfile after "$evidence_dir/close/events-after.json" '
        ($before[0].partitions) as $beforeRows
        | ($after[0].partitions) as $afterRows
        | any(
            $afterRows[];
            . as $after
            | any(
                $beforeRows[];
                .partition == $after.partition and .offset < $after.offset
              )
          )
      ' >/dev/null; then
    fail 'close workflow produced no terminal matching.events record for the resting order'
    return 1
  fi

  wait_critical_consumers_to_close \
    "$evidence_dir/close/events-after.json" \
    "$evidence_dir/close/consumer-state.json" || {
    fail 'critical consumers did not drain through the close workflow event positions'
    return 1
  }
  wait_order_projection_status \
    "$order_id" EXPIRED "$evidence_dir/close/expired-order.json" || {
    fail 'resting order did not become EXPIRED after Close Barrier processing'
    return 1
  }

  current_stage="stage successful certification verdict"
  pending_pass_verdict="$evidence_dir/verdict.pending.json"
  jq -n \
    --arg status PASS \
    --arg namespace "$namespace" \
    --arg tradingSessionId "$trading_session_id" \
    --arg orderId "$order_id" \
    --slurpfile closeResponse "$close_response" \
    --slurpfile commandsBefore "$evidence_dir/close/commands-before.json" \
    --slurpfile commandsAfter "$evidence_dir/close/commands-after.json" \
    --slurpfile matchingClosed "$evidence_dir/close/matching-closed.json" \
    --slurpfile expiredOrder "$evidence_dir/close/expired-order.json" '
      {
        status:$status,
        namespace:$namespace,
        tradingSessionId:$tradingSessionId,
        orderId:$orderId,
        gatewayClose:$closeResponse[0],
        commandPositionsBefore:$commandsBefore[0],
        commandPositionsAfter:$commandsAfter[0],
        matchingClosed:$matchingClosed[0],
        expiredOrder:$expiredOrder[0],
        proven:[
          "Gateway admission entered CLOSED before downstream drain was declared complete",
          "matching.commands advanced exactly once on partitions 0 through 14",
          "all 15 Matching consumers committed through their Close Barrier",
          "all 15 Matching runtimes reported CLOSED with no pending input or publication",
          "all three critical consumers caught up through close-workflow matching.events positions",
          "the pre-close resting order became EXPIRED"
        ],
        outOfScope:[
          "Issue #160 production live-observation adapters",
          "external production promotion",
          "exactly-once network delivery to a disconnected FIX client"
        ]
      }
    ' >"$pending_pass_verdict"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi

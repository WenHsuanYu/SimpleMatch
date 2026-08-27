#!/usr/bin/env bash

# External FIX, Kafka Connect, and Gateway operations interfaces used by the test.

start_port_forward() {
  local resource="$1"
  local remote_port="$2"
  local log_path="$3"
  local pid_variable="$4"
  local port_variable="$5"
  local pid
  local port=""

  : >"$log_path"
  kns port-forward "$resource" ":$remote_port" >"$log_path" 2>&1 &
  pid="$!"
  printf -v "$pid_variable" '%s' "$pid"

  for _ in $(seq 1 60); do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      cat "$log_path" >&2
      return 1
    fi
    port="$(sed -nE "s/.*127\\.0\\.0\\.1:([0-9]+) -> $remote_port.*/\\1/p" "$log_path" | tail -n 1)"
    if [[ -n "$port" ]]; then
      printf -v "$port_variable" '%s' "$port"
      return 0
    fi
    sleep 1
  done
  return 1
}

stop_background_process() {
  local pid="${1:-}"
  [[ -n "$pid" ]] || return 0
  kill "$pid" >/dev/null 2>&1 || true
  wait "$pid" >/dev/null 2>&1 || true
}

start_fix_port_forward() {
  stop_background_process "${fix_port_forward_pid:-}"
  fix_port_forward_pid=""
  fix_port=""
  start_port_forward service/quickfix-gateway-owner-0 5001 \
    "$evidence_dir/fix/port-forward.log" fix_port_forward_pid fix_port ||
    die 'QuickFIX port-forward did not become ready'
}

stop_fix_port_forward() {
  stop_background_process "${fix_port_forward_pid:-}"
  fix_port_forward_pid=""
  fix_port=""
}

run_fix_phase() {
  local phase="$1"
  local evidence="$2"
  shift 2
  env \
    SIMPLEMATCH_RETAINED_FIX_PHASE="$phase" \
    SIMPLEMATCH_LIVE_FIX_HOST=127.0.0.1 \
    SIMPLEMATCH_LIVE_FIX_PORT="$fix_port" \
    SIMPLEMATCH_LIVE_FIX_ACCOUNT_ID="$account_id" \
    SIMPLEMATCH_LIVE_FIX_SYMBOL="$symbol" \
    SIMPLEMATCH_LIVE_FIX_QUANTITY="$quantity" \
    SIMPLEMATCH_LIVE_FIX_PRICE="$price" \
    SIMPLEMATCH_LIVE_FIX_CL_ORD_ID="$cl_ord_id" \
    SIMPLEMATCH_RETAINED_FIX_STATE_DIR="$evidence_dir/client-state" \
    SIMPLEMATCH_RETAINED_FIX_EVIDENCE="$evidence" \
    SIMPLEMATCH_RETAINED_FIX_TIMEOUT_SECONDS="$timeout_seconds" \
    "$@" \
    "$repo_root/gradlew" --no-daemon \
      :services:quickfix-gateway:retainedSessionCertificationTest
}

start_fix_submit_client() {
  fix_ready_file="$evidence_dir/fix/submit-client-ready"
  fix_release_file="$evidence_dir/fix/submit-client-release"
  fix_submit_log="$evidence_dir/fix/submit-client.log"
  rm -f "$fix_ready_file" "$fix_release_file" "$evidence_dir/fix/submit.json"

  env \
    SIMPLEMATCH_LIVE_FIX_HOST=127.0.0.1 \
    SIMPLEMATCH_LIVE_FIX_PORT="$fix_port" \
    SIMPLEMATCH_LIVE_FIX_ACCOUNT_ID="$account_id" \
    SIMPLEMATCH_LIVE_FIX_SYMBOL="$symbol" \
    SIMPLEMATCH_LIVE_FIX_QUANTITY="$quantity" \
    SIMPLEMATCH_LIVE_FIX_PRICE="$price" \
    SIMPLEMATCH_LIVE_FIX_CL_ORD_ID="$cl_ord_id" \
    SIMPLEMATCH_RETAINED_FIX_STATE_DIR="$evidence_dir/client-state" \
    SIMPLEMATCH_RETAINED_FIX_EVIDENCE="$evidence_dir/fix/submit.json" \
    SIMPLEMATCH_RETAINED_FIX_READY_FILE="$fix_ready_file" \
    SIMPLEMATCH_RETAINED_FIX_RELEASE_FILE="$fix_release_file" \
    SIMPLEMATCH_RETAINED_FIX_TIMEOUT_SECONDS="$timeout_seconds" \
    "$repo_root/gradlew" --no-daemon \
      :services:quickfix-gateway:preparedSubmissionCertificationTest \
      >"$fix_submit_log" 2>&1 &
  fix_submit_pid="$!"

  for _ in $(seq 1 "$timeout_seconds"); do
    [[ -f "$fix_ready_file" ]] && return 0
    if ! kill -0 "$fix_submit_pid" >/dev/null 2>&1; then
      cat "$fix_submit_log" >&2
      return 1
    fi
    sleep 1
  done
  return 1
}

release_fix_submit_client() {
  : >"$fix_release_file"
}

wait_fix_submit_client() {
  local status=0
  wait "$fix_submit_pid" || status="$?"
  fix_submit_pid=""
  if (( status != 0 )); then
    cat "$fix_submit_log" >&2
    return "$status"
  fi
  [[ -s "$evidence_dir/fix/submit.json" ]]
}

start_connect_port_forward() {
  stop_background_process "${connect_port_forward_pid:-}"
  connect_port_forward_pid=""
  connect_port=""
  start_port_forward service/kafka-connect 8083 \
    "$evidence_dir/submission/kafka-connect-port-forward.log" \
    connect_port_forward_pid connect_port ||
    die 'Kafka Connect port-forward did not become ready'
}

stop_connect_port_forward() {
  stop_background_process "${connect_port_forward_pid:-}"
  connect_port_forward_pid=""
  connect_port=""
}

connect_request() {
  local method="$1"
  local path="$2"
  local destination="$3"
  local status
  status="$(curl --connect-timeout 5 --max-time 15 -sS -o "$destination" -w '%{http_code}' \
    -X "$method" "http://127.0.0.1:${connect_port}${path}")" || return 1
  [[ "$status" == 2?? ]]
}

wait_outbox_connector_state() {
  local expected="$1"
  local destination="$2"
  for _ in $(seq 1 "$timeout_seconds"); do
    if connect_request GET /connectors/risk-service-outbox/status "$destination" \
        && jq -e --arg expected "$expected" '
          .connector.state == $expected
          and (.tasks | length > 0)
          and all(.tasks[]; .state == $expected)
        ' "$destination" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

pause_risk_outbox() {
  connect_request PUT /connectors/risk-service-outbox/pause \
    "$evidence_dir/submission/outbox-pause-response.txt" ||
    die 'Kafka Connect rejected risk-service-outbox pause request'
  outbox_connector_paused=true
  wait_outbox_connector_state PAUSED "$evidence_dir/submission/outbox-paused-status.json" ||
    die 'risk-service-outbox did not reach PAUSED state'
}

resume_risk_outbox() {
  connect_request PUT /connectors/risk-service-outbox/resume \
    "$evidence_dir/submission/outbox-resume-response.txt" ||
    die 'Kafka Connect rejected risk-service-outbox resume request'
  outbox_connector_paused=false
  wait_outbox_connector_state RUNNING "$evidence_dir/submission/outbox-running-status.json" ||
    die 'risk-service-outbox did not reach RUNNING state'
}

gateway_override_is_absent() {
  local name="$1"
  ! kns get statefulset quickfix-gateway -o json |
    jq -e --arg name "$name" '
      .spec.template.spec.containers[]
      | select(.name == "quickfix-gateway")
      | .env[]?
      | select(.name == $name)
    ' >/dev/null
}

enable_gateway_operations() {
  local names=(
    SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_HTTP_ENABLED
    SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_AUTOMATIC_CLOSE_ENABLED
    SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_OPERATOR_TOKEN
  )
  local name
  for name in "${names[@]}"; do
    gateway_override_is_absent "$name" ||
      die "QuickFIX Gateway already defines certification override $name"
  done
  gateway_operator_token="$(cat /proc/sys/kernel/random/uuid)"
  kns set env statefulset/quickfix-gateway \
    SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_HTTP_ENABLED=true \
    SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_AUTOMATIC_CLOSE_ENABLED=false \
    SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_OPERATOR_TOKEN="$gateway_operator_token" >/dev/null
  gateway_env_modified=true
  kns rollout status statefulset/quickfix-gateway --timeout="${timeout_seconds}s" >/dev/null
}

restore_gateway_environment() {
  [[ "$gateway_env_modified" == true ]] || return 0
  kns set env statefulset/quickfix-gateway \
    SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_HTTP_ENABLED- \
    SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_AUTOMATIC_CLOSE_ENABLED- \
    SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_OPERATOR_TOKEN- >/dev/null 2>&1 || {
      restoration_failed=true
      return 0
    }
  gateway_env_modified=false
}

start_gateway_port_forward() {
  stop_background_process "${gateway_port_forward_pid:-}"
  gateway_port_forward_pid=""
  gateway_port=""
  start_port_forward pod/quickfix-gateway-0 8080 \
    "$evidence_dir/baseline/gateway-management-port-forward.log" \
    gateway_port_forward_pid gateway_port ||
    die 'Gateway management port-forward did not become ready'
}

stop_gateway_port_forward() {
  stop_background_process "${gateway_port_forward_pid:-}"
  gateway_port_forward_pid=""
  gateway_port=""
}

accepted_observation_attempt_dir() {
  local payload="$1"
  local prefix="$evidence_dir/baseline/gateway-observation-"
  [[ "$payload" == "$prefix"*.json ]] || return 1

  local check="${payload#"$prefix"}"
  check="${check%.json}"
  [[ -n "$check" ]] || return 1

  local result
  for result in "$evidence_dir"/baseline/observation-"$check"-attempt-*/result.json; do
    [[ -f "$result" ]] || continue
    if jq -e '.exitStatus == 0' "$result" >/dev/null 2>&1; then
      printf '%s\n' "${result%/result.json}"
      return 0
    fi
  done
  return 1
}

gateway_observation_request_identity() {
  local destination="$1"
  local payload="$2"
  local observation_prefix="$evidence_dir/baseline/gateway-observation-"
  [[ "$payload" == "$observation_prefix"*.json ]] || return 1

  local check="${payload#"$observation_prefix"}"
  check="${check%.json}"
  [[ -n "$check" ]] || return 1

  local response_prefix="$evidence_dir/baseline/gateway-observation-${check}-gateway-attempt-"
  [[ "$destination" == "$response_prefix"*.json ]] || return 1
  local gateway_attempt="${destination#"$response_prefix"}"
  gateway_attempt="${gateway_attempt%.json}"
  [[ "$gateway_attempt" =~ ^[1-9][0-9]*$ ]] || return 1
  printf '%s %s\n' "$check" "$gateway_attempt"
}

archive_gateway_observation_attempts() {
  local destination="$1"
  local payload="$2"
  local identity check gateway_attempt
  identity="$(gateway_observation_request_identity "$destination" "$payload")" || return 1
  read -r check gateway_attempt <<<"$identity"

  local attempt_dir suffix archived_dir
  for attempt_dir in "$evidence_dir"/baseline/observation-"$check"-attempt-*; do
    [[ -d "$attempt_dir" ]] || continue
    suffix="${attempt_dir##*/observation-${check}-}"
    archived_dir="$evidence_dir/baseline/observation-${check}-gateway-${gateway_attempt}-${suffix}"
    [[ ! -e "$archived_dir" ]] || return 1
    mv -- "$attempt_dir" "$archived_dir" || return 1
  done
}

record_gateway_observation_submission_timing() {
  local attempt_dir="$1"
  local timing="$attempt_dir/timing.json"
  [[ -f "$timing" ]] || return 1

  local started completed oldest_source maximum_fact_age
  local age_at_start age_at_completion
  local remaining_at_start remaining_at_completion
  started="$(cat "$attempt_dir/gateway-submission-started-at" 2>/dev/null || true)"
  completed="$(cat "$attempt_dir/gateway-submission-completed-at" 2>/dev/null || true)"
  [[ "$started" =~ ^[0-9]+$ && "$completed" =~ ^[0-9]+$ ]] || return 1

  oldest_source="$(jq -r '.matchingRuntimeFreshness.oldestSourceEpochMs // empty' "$timing")"
  maximum_fact_age="$(jq -r '.matchingRuntimeFreshness.maximumFactAgeMillis // empty' "$timing")"
  age_at_start=null
  age_at_completion=null
  remaining_at_start=null
  remaining_at_completion=null
  if [[ "$oldest_source" =~ ^[0-9]+$ ]]; then
    age_at_start="$((started - oldest_source))"
    age_at_completion="$((completed - oldest_source))"
    if [[ "$maximum_fact_age" =~ ^[0-9]+$ ]]; then
      remaining_at_start="$((maximum_fact_age - age_at_start))"
      remaining_at_completion="$((maximum_fact_age - age_at_completion))"
    fi
  fi

  local updated
  updated="$(
    jq \
      --argjson started "$started" \
      --argjson completed "$completed" \
      --argjson ageAtStart "$age_at_start" \
      --argjson ageAtCompletion "$age_at_completion" \
      --argjson remainingAtStart "$remaining_at_start" \
      --argjson remainingAtCompletion "$remaining_at_completion" '
        .gatewaySubmission = {
          startedEpochMs:$started,
          completedEpochMs:$completed,
          durationMillis:($completed - $started)
        }
        | .matchingRuntimeFreshness += {
            oldestSourceAgeAtSubmissionStartMillis:$ageAtStart,
            oldestSourceAgeAtSubmissionCompletionMillis:$ageAtCompletion,
            remainingBudgetAtSubmissionStartMillis:$remainingAtStart,
            remainingBudgetAtSubmissionCompletionMillis:$remainingAtCompletion
          }
      ' "$timing"
  )" || return 1
  printf '%s\n' "$updated" >"$timing"
}

gateway_request() {
  local method="$1"
  local path="$2"
  local destination="$3"
  local payload="${4:-}"
  local -a request=(
    curl --connect-timeout 5 --max-time 15 -sS -o "$destination" -w '%{http_code}'
    -X "$method"
    -H "X-SimpleMatch-Operator-Token: $gateway_operator_token"
  )
  if [[ -n "$payload" ]]; then
    request+=(-H 'Content-Type: application/json' --data-binary "@$payload")
  fi

  local observation_attempt_dir=""
  if [[ "$method" == POST && "$path" == /operations/observations && -n "$payload" ]]; then
    observation_attempt_dir="$(accepted_observation_attempt_dir "$payload" || true)"
    if [[ -n "$observation_attempt_dir" ]]; then
      date +%s%3N >"$observation_attempt_dir/gateway-submission-started-at"
    fi
  fi

  local status
  local request_status=0
  status="$("${request[@]}" "http://127.0.0.1:${gateway_port}${path}")" ||
    request_status="$?"

  if [[ -n "$observation_attempt_dir" ]]; then
    date +%s%3N >"$observation_attempt_dir/gateway-submission-completed-at"
    record_gateway_observation_submission_timing "$observation_attempt_dir" || return 1
    archive_gateway_observation_attempts "$destination" "$payload" || return 1
  fi

  (( request_status == 0 )) || return "$request_status"
  [[ "$status" == 2?? ]]
}

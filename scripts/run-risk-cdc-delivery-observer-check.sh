#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-resilience.sh
source "$script_dir/lib/local-resilience.sh"
# shellcheck source=scripts/lib/cdc-observer-fixture.sh
source "$script_dir/lib/cdc-observer-fixture.sh"

namespace=""
expected_namespace_run_id=""
evidence_dir=""
timeout_seconds="${SIMPLEMATCH_CDC_OBSERVER_TIMEOUT_SECONDS:-180}"
cleanup_reserve_seconds=30
observer_deadline_epoch=0
active_deadline_epoch=0
maximum_metric_age_seconds=""
kind_cluster="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
kind_context="kind-${kind_cluster}"
matching_fixture_default="$script_dir/../out/build/full-native-dev/simplematch-matching-kafka-fixture-publisher"
matching_fixture_validator="${SIMPLEMATCH_MATCHING_FIXTURE_PUBLISHER_BIN:-$matching_fixture_default}"
matching_fixture_validator_sha256=""
matching_trading_day=""
matching_trading_session_id=""
matching_image_digest=""
artifact_sha256=""
artifact_routing_version=""

connect_port_forward_pid=""
risk_port_forward_pid=""
connect_port=""
risk_port=""
connector_paused=false
cleanup_resume_status=not-needed
postgres_pod=""
kafka_pod=""
risk_pod=""
event_id=""
headers_json=""
headers_json_sql=""

usage() {
  cat <<'EOF'
Usage:
  scripts/run-risk-cdc-delivery-observer-check.sh \
    --namespace NAME \
    --namespace-run-id RUN_ID \
    --evidence-dir PATH \
    [--timeout-seconds N]

Pauses the retained Risk Debezium connector, inserts one valid duplicate Open Barrier
outbox event, and proves durable CDC lag/age evidence transitions from pending to
observed after connector recovery. The namespace and its Flyway/workload resources
must already have been created by the local production-like certification workflow. The
observer accepts only a disposable namespace managed by that workflow whose run-id label
exactly matches --namespace-run-id.
The native fixture publisher must already be built; set
SIMPLEMATCH_MATCHING_FIXTURE_PUBLISHER_BIN to override its default path.
EOF
}

die() {
  printf 'Risk CDC observer: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) namespace="${2:?--namespace requires a value}"; shift 2 ;;
    --namespace-run-id)
      expected_namespace_run_id="${2:?--namespace-run-id requires a value}"
      shift 2
      ;;
    --evidence-dir) evidence_dir="${2:?--evidence-dir requires a value}"; shift 2 ;;
    --timeout-seconds) timeout_seconds="${2:?--timeout-seconds requires a value}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

[[ -n "$namespace" ]] || { usage >&2; die '--namespace is required'; }
[[ -n "$expected_namespace_run_id" ]] || {
  usage >&2
  die '--namespace-run-id is required'
}
[[ -n "$evidence_dir" ]] || { usage >&2; die '--evidence-dir is required'; }
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die \
  '--timeout-seconds must be a positive integer'
(( timeout_seconds > cleanup_reserve_seconds )) || die \
  "--timeout-seconds must exceed ${cleanup_reserve_seconds}s cleanup reserve"
(( timeout_seconds <= 600 )) || die '--timeout-seconds must not exceed 600'
observer_deadline_epoch=$(( $(date +%s) + timeout_seconds ))
active_deadline_epoch=$(( observer_deadline_epoch - cleanup_reserve_seconds ))

for tool in kubectl jq curl date seq sleep od tr grep sed tail cat sha256sum awk timeout; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done

[[ -x "$matching_fixture_validator" ]] || die \
  "Matching Open Barrier validator is missing or not executable: $matching_fixture_validator"
matching_fixture_validator_sha256="$(sha256sum "$matching_fixture_validator" | awk '{print $1}')" || die \
  "could not fingerprint the Matching Open Barrier validator: $matching_fixture_validator"
[[ "$matching_fixture_validator_sha256" =~ ^[0-9a-f]{64}$ ]] || die \
  "Matching Open Barrier validator fingerprint is not canonical: $matching_fixture_validator"

mkdir -p "$evidence_dir"
evidence_dir="$(cd -- "$evidence_dir" && pwd)"
run_id="cdc-observer-$(date -u +%Y%m%d-%H%M%S)-$$"
connector_base_url=""
risk_base_url=""
command_partition=""
command_key=""
payload_hex=""

current_epoch_millis() {
  date +%s%3N
}

remaining_seconds() {
  local remaining
  remaining=$((active_deadline_epoch - $(date +%s)))
  (( remaining > 0 )) || return 1
  printf '%s\n' "$remaining"
}

bounded_sleep() {
  local requested="$1" remaining
  remaining="$(remaining_seconds)" || return 1
  (( remaining > 1 )) || return 1
  (( requested < remaining )) || requested=$((remaining - 1))
  sleep "$requested"
}

curl_with_deadline() {
  local remaining connect_timeout
  remaining="$(remaining_seconds)" || return 124
  connect_timeout=3
  (( connect_timeout < remaining )) || connect_timeout=$remaining
  curl --connect-timeout "$connect_timeout" --max-time "$remaining" -fsS "$@"
}

kubectl_with_deadline() {
  local remaining
  remaining="$(remaining_seconds)" || return 124
  timeout "$remaining" kubectl --request-timeout="${remaining}s" \
    "$@"
}

kns() {
  kubectl_with_deadline --context "$kind_context" -n "$namespace" "$@"
}

capture_safe_diagnostic_log() {
  local output="$1"
  shift
  kns logs "$@" >"$output" 2>&1 || true
  if ! resilience_log_is_safe "$output"; then
    printf '%s\n' \
      'diagnostic log omitted after the sensitive-log safety check failed' \
      >"$output"
  fi
}

collect_diagnostics() {
  kns get pods -o wide >"$evidence_dir/diagnostics-pods.txt" 2>&1 || true
  kns get deployments,statefulsets,jobs >"$evidence_dir/diagnostics-workloads.txt" 2>&1 || true
  capture_safe_diagnostic_log "$evidence_dir/diagnostics-risk.log" \
    -l app.kubernetes.io/name=risk-service --all-containers=true \
    --prefix=true --tail=300
  capture_safe_diagnostic_log "$evidence_dir/diagnostics-connect.log" \
    -l app.kubernetes.io/name=kafka-connect --all-containers=true \
    --prefix=true --tail=200
  if [[ -n "$connector_base_url" ]]; then
    curl_with_deadline \
      "$connector_base_url/connectors/risk-service-outbox/status" \
      >"$evidence_dir/diagnostics-connector-status.json" 2>&1 || true
  fi
  if [[ -n "$risk_base_url" ]]; then
    curl_with_deadline "$risk_base_url/actuator/health" \
      >"$evidence_dir/diagnostics-risk-health.json" 2>&1 || true
  fi
}

cleanup_resume_connector() {
  local output
  cleanup_resume_status=resume-requested
  if ! curl_with_deadline -X PUT \
      "$connector_base_url/connectors/risk-service-outbox/resume" >/dev/null 2>&1; then
    cleanup_resume_status=resume-request-failed
    return 1
  fi
  while :; do
    output="$(curl_with_deadline \
      "$connector_base_url/connectors/risk-service-outbox/status" \
      2>/dev/null || true)"
    if jq -e '
        .connector.state == "RUNNING" and
        (.tasks | length) > 0 and ([.tasks[].state] | all(. == "RUNNING"))
      ' >/dev/null 2>&1 <<<"$output"; then
      cleanup_resume_status=running
      connector_paused=false
      return 0
    fi
    if ! bounded_sleep 1; then
      cleanup_resume_status='deadline-exceeded'
      return 1
    fi
  done
}

cleanup() {
  local exit_code="$?"
  local failure_reason='Risk CDC observer phase failed; inspect diagnostics'
  # Normal work stops before this reserved window. Switch to the total
  # deadline so connector recovery and diagnostics retain bounded time even
  # when the observer failed at the end of its operational budget.
  active_deadline_epoch="$observer_deadline_epoch"
  if [[ "$connector_paused" == true ]]; then
    if [[ -n "$connector_base_url" ]]; then
      cleanup_resume_connector || true
    else
      cleanup_resume_status=endpoint-unavailable
    fi
  fi
  if [[ "$exit_code" -ne 0 ]]; then
    if [[ "$cleanup_resume_status" != running &&
      "$cleanup_resume_status" != not-needed ]]; then
      failure_reason+="; connector cleanup status=$cleanup_resume_status"
    fi
    # Capture HTTP and workload diagnostics while port-forwards are still
    # available. They are terminated only after this bounded best effort.
    collect_diagnostics
  fi
  if [[ -n "$connect_port_forward_pid" ]]; then
    kill "$connect_port_forward_pid" >/dev/null 2>&1 || true
    wait "$connect_port_forward_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$risk_port_forward_pid" ]]; then
    kill "$risk_port_forward_pid" >/dev/null 2>&1 || true
    wait "$risk_port_forward_pid" >/dev/null 2>&1 || true
  fi
  if [[ "$exit_code" -ne 0 ]]; then
    if [[ ! -f "$evidence_dir/verdict.json" ]]; then
      jq -n --arg status FAIL --arg runId "$run_id" \
        --arg cleanupResumeStatus "$cleanup_resume_status" \
        --arg reason "$failure_reason" \
        '{status:$status,runId:$runId,
          cleanupResumeStatus:$cleanupResumeStatus,reason:$reason}' \
        >"$evidence_dir/verdict.json" || true
    fi
  fi
  trap - EXIT
  exit "$exit_code"
}
trap cleanup EXIT

remaining_seconds >/dev/null || die \
  'observer deadline expired before Kubernetes context validation'
current_context="$(kubectl_with_deadline config current-context)" || die \
  'could not read the current Kubernetes context'
[[ "$current_context" == "$kind_context" ]] || die \
  "current Kubernetes context=$current_context, expected $kind_context"
namespace_json="$(kubectl_with_deadline --context "$kind_context" get namespace "$namespace" \
  -o json)" || die "namespace does not exist: $namespace"
jq -e --arg expected local-production-like-certification '
  .metadata.labels["simplematch.io/lifecycle"] == "disposable" and
  .metadata.labels["simplematch.io/managed-by"] == $expected
' <<<"$namespace_json" >/dev/null || die \
  "namespace is not an owned disposable local certification namespace: $namespace"
namespace_run_id="$(jq -er \
  '.metadata.labels["simplematch.io/run-id"] | strings | select(length > 0)' \
  <<<"$namespace_json")" || die \
  "certification namespace has no non-empty run-id label: $namespace"
[[ "$namespace_run_id" == "$expected_namespace_run_id" ]] || die \
  "certification namespace run-id does not match the requested run: $namespace"
matching_session_config_json="$(kns get configmap matching-session-config -o json)" || die \
  'could not read the deployed Matching session configuration'
matching_trading_day="$(jq -er \
  '.data.trading_day | strings | select(length > 0)' \
  <<<"$matching_session_config_json")" || die \
  'deployed Matching session configuration has no trading day'
matching_trading_session_id="$(jq -er \
  '.data.trading_session_id | strings | select(length > 0)' \
  <<<"$matching_session_config_json")" || die \
  'deployed Matching session configuration has no trading session'
matching_image_digest="$(jq -er \
  '.data.matching_image_digest | strings | select(length > 0)' \
  <<<"$matching_session_config_json")" || die \
  'deployed Matching session configuration has no image digest'
[[ "$matching_image_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || die \
  'deployed Matching image digest is not canonical'
matching_artifact_config_json="$(kns get configmap matching-daily-artifact -o json)" || die \
  'could not read the deployed Matching artifact configuration'
artifact_json="$(jq -j -er \
  '.binaryData["market_reference.json"] | strings | @base64d' \
  <<<"$matching_artifact_config_json")" || die \
  'deployed Matching artifact JSON is missing or invalid'
artifact_sha256="$(jq -j -er \
  '.binaryData["market_reference.sha256"] | strings | @base64d' \
  <<<"$matching_artifact_config_json" | tr -d '[:space:]')" || die \
  'deployed Matching artifact checksum is missing or invalid'
[[ "$artifact_sha256" =~ ^[0-9a-f]{64}$ ]] || die \
  'deployed Matching artifact checksum is not canonical'
artifact_computed_sha256="$(printf '%s' "$artifact_json" | sha256sum)"
artifact_computed_sha256="${artifact_computed_sha256%% *}"
[[ "$artifact_computed_sha256" == "$artifact_sha256" ]] || die \
  'deployed Matching artifact checksum does not match its content'
artifact_trading_day="$(jq -er \
  '.metadata.tradingDay | strings | select(length > 0)' <<<"$artifact_json")" || die \
  'deployed Matching artifact has no trading day metadata'
[[ "$artifact_trading_day" == "$matching_trading_day" ]] || die \
  'deployed Matching artifact trading day does not match the session configuration'
artifact_routing_version="$(jq -er \
  '.metadata.routingAlgorithmVersion | strings | select(length > 0)' \
  <<<"$artifact_json")" || die \
  'deployed Matching artifact has no routing algorithm version'
maximum_metric_age_seconds="$(kns get configmap risk-service-config -o json |
  jq -er '.data["application.yaml"] | capture("maximum-metric-age: (?<seconds>[0-9]+)s").seconds')" || die \
  'could not read the deployed Risk maximum-metric-age configuration'
[[ "$maximum_metric_age_seconds" =~ ^[1-9][0-9]*$ ]] || die \
  'deployed Risk maximum-metric-age must be a positive integer number of seconds'
(( maximum_metric_age_seconds <= 600 )) || die \
  'deployed Risk maximum-metric-age must not exceed 600 seconds'

start_port_forward() {
  local target="$1" remote_port="$2" log_path="$3" pid_variable="$4"
  local port_variable="$5" output pid
  kns port-forward "$target" ":$remote_port" >"$log_path" 2>&1 &
  pid="$!"
  # Publish the child PID before polling so EXIT cleanup can reap a failed
  # startup, including the path where port-forward exits before returning.
  printf -v "$pid_variable" '%s' "$pid"
  printf -v "$port_variable" '%s' ''
  while :; do
    remaining_seconds >/dev/null || die \
      "port-forward startup exceeded the observer deadline for $target"
    kill -0 "$pid" >/dev/null 2>&1 || {
      cat "$log_path" >&2
      die "port-forward exited for $target"
    }
    output="$(sed -nE 's/.*127\.0\.0\.1:([0-9]+) -> [0-9]+.*/\1/p' "$log_path" | tail -n 1)"
    if [[ "$output" =~ ^[0-9]+$ ]]; then
      printf -v "$port_variable" '%s' "$output"
      return 0
    fi
    bounded_sleep 1 || die \
      "port-forward startup exceeded the observer deadline for $target"
  done
}

wait_for_connector_state() {
  local connector="$1" expected="$2" output
  [[ -n "$connector" && -n "$expected" ]] || die \
    'connector name and expected state are required'
  while :; do
    remaining_seconds >/dev/null || die \
      "$connector did not become $expected before the observer deadline"
    output="$(curl_with_deadline \
      "$connector_base_url/connectors/$connector/status" \
      2>/dev/null || true)"
    if jq -e --arg expected "$expected" '
        .connector.state == $expected and
        (.tasks | length) > 0 and ([.tasks[].state] | all(. == $expected))
      ' >/dev/null 2>&1 <<<"$output"; then
      printf '%s\n' "$output"
      return 0
    fi
    bounded_sleep 1 || die \
      "$connector did not become $expected before the observer deadline"
  done
}

capture_retained_connector_states() {
  local output="$1" risk_status_file="$2" risk_status account_status marketdata_status
  risk_status="$(cat "$risk_status_file")"
  account_status="$(wait_for_connector_state account-service-outbox RUNNING)"
  marketdata_status="$(wait_for_connector_state marketdata-publisher-outbox RUNNING)"
  jq -n \
    --argjson risk "$risk_status" \
    --argjson account "$account_status" \
    --argjson marketdata "$marketdata_status" \
    '{riskServiceOutbox:$risk,accountServiceOutbox:$account,
      marketdataPublisherOutbox:$marketdata}' >"$output"
}

sql() {
  local statement="$1"
  kns exec "$postgres_pod" -- psql -U simplematch -d simplematch \
    -At -v ON_ERROR_STOP=1 -c "$statement"
}

read_metric_row() {
  sql "
    SELECT lag_events || '|' || updated_at_unix_ms
    FROM risk_service.cdc_delivery_lag
    WHERE metric_name = 'matching.commands';
  "
}

wait_for_metric_row() {
  local expected_lag="$1" minimum_updated_at="$2" failure_message="$3"
  local output lag updated lag_matches
  case "$expected_lag" in
    zero|positive) ;;
    *) die "unsupported CDC metric lag expectation: $expected_lag" ;;
  esac
  while :; do
    remaining_seconds >/dev/null || die "$failure_message"
    output="$(read_metric_row 2>/dev/null || true)"
    IFS='|' read -r lag updated <<<"$output"
    lag_matches=false
    case "$expected_lag" in
      zero) [[ "$lag" == 0 ]] && lag_matches=true ;;
      positive) [[ "$lag" =~ ^[1-9][0-9]*$ ]] && lag_matches=true ;;
    esac
    if [[ "$updated" =~ ^[1-9][0-9]*$ && "$updated" -gt "$minimum_updated_at" &&
      "$lag_matches" == true ]]; then
      printf '%s\n' "$output"
      return 0
    fi
    bounded_sleep 2 || die "$failure_message"
  done
}

metric_json() {
  local metric="$1" output="$2" expected="$3" minimum_value="${4:-0}" metric_url response
  case "$expected" in
    zero|positive|nonnegative) ;;
    at-least)
      [[ "$minimum_value" =~ ^[0-9]+$ ]] || die \
        "minimum Actuator metric value must be a non-negative integer: $minimum_value"
      ;;
    *) die "unsupported Actuator metric expectation: $expected" ;;
  esac
  metric_url="$risk_base_url/actuator/metrics/simplematch.delivery.observations"
  metric_url+="?tag=component:risk-cdc-delivery&tag=metric:${metric}"
  while :; do
    remaining_seconds >/dev/null || die "Actuator metric is missing for $metric"
    response="$(curl_with_deadline "$metric_url" 2>/dev/null || true)"
    if jq -e --arg expected "$expected" --arg minimum "$minimum_value" '
        .name == "simplematch.delivery.observations" and
        (.measurements | length) > 0 and
        (.measurements | all(.[];
          (.value | type == "number") and
          ($expected == "nonnegative" or
            ($expected == "zero" and .value == 0) or
            ($expected == "positive" and .value >= 1) or
            ($expected == "at-least" and .value >= ($minimum | tonumber)))))
      ' >/dev/null 2>&1 <<<"$response"; then
      printf '%s\n' "$response" >"$output"
      return 0
    fi
    bounded_sleep 2 || die "Actuator metric is missing for $metric"
  done
}

assert_metric_measurement_is_nonnegative() {
  local output="$1"
  jq -e '
    (.measurements | length > 0) and
    (.measurements | all(.[]; (.value | type == "number" and . >= 0)))
  ' "$output" >/dev/null || die \
    "Actuator metric contains no numeric non-negative measurement: $output"
}

wait_for_observation() {
  local output
  while :; do
    remaining_seconds >/dev/null || die \
      'Risk CDC observation row did not appear before the observer deadline'
    output="$(sql "
      SELECT topic || '|' || partition_id || '|' || kafka_offset || '|'
          || observed_at_unix_ms
      FROM risk_service.cdc_delivery_observation
      WHERE event_id = '$event_id'::uuid;
    " 2>/dev/null || true)"
    if [[ "$output" =~ ^matching\.commands\|[0-9]+\|[0-9]+\|[1-9][0-9]*$ ]]; then
      printf '%s\n' "$output"
      return 0
    fi
    bounded_sleep 2 || die \
      'Risk CDC observation row did not appear before the observer deadline'
  done
}

for rollout_target in \
  deployment/risk-service deployment/kafka-connect statefulset/postgres; do
  rollout_remaining="$(remaining_seconds)" || die \
    "rollout exceeded the observer deadline: $rollout_target"
  kns rollout status "$rollout_target" --timeout="${rollout_remaining}s" >/dev/null || die \
    "rollout did not complete before the observer deadline: $rollout_target"
done
postgres_pod="$(kns get pods -l app.kubernetes.io/name=postgres \
  -o jsonpath='{.items[0].metadata.name}')"
kafka_pod="$(kns get pods -l app.kubernetes.io/name=kafka \
  -o jsonpath='{.items[0].metadata.name}')"
risk_pod="$(kns get pods -l app.kubernetes.io/name=risk-service \
  -o jsonpath='{.items[0].metadata.name}')"
[[ -n "$postgres_pod" && -n "$kafka_pod" && -n "$risk_pod" ]] || die \
  'Risk CDC observer prerequisites are missing'

start_port_forward service/kafka-connect 8083 "$evidence_dir/connect-port-forward.log" \
  connect_port_forward_pid connect_port
connector_base_url="http://127.0.0.1:${connect_port}"
wait_for_connector_state risk-service-outbox RUNNING \
  >"$evidence_dir/connector-running-before.json"
capture_retained_connector_states "$evidence_dir/connectors-running-before.json" \
  "$evidence_dir/connector-running-before.json"

start_port_forward "pod/$risk_pod" 8080 "$evidence_dir/risk-port-forward.log" \
  risk_port_forward_pid risk_port
risk_base_url="http://127.0.0.1:${risk_port}"
curl_with_deadline "$risk_base_url/actuator/health/liveness" \
  >"$evidence_dir/health-liveness.json"
curl_with_deadline "$risk_base_url/actuator/health/readiness" \
  >"$evidence_dir/health-readiness.json"
metric_json connector_lag_events "$evidence_dir/metric-before-lag.json" zero
metric_json outbox_age_millis "$evidence_dir/metric-before-age.json" nonnegative
metric_json observation_updated_at_unix_ms \
  "$evidence_dir/metric-before-updated-at.json" nonnegative
assert_metric_measurement_is_nonnegative "$evidence_dir/metric-before-lag.json"
assert_metric_measurement_is_nonnegative "$evidence_dir/metric-before-age.json"
assert_metric_measurement_is_nonnegative "$evidence_dir/metric-before-updated-at.json"
jq -e '.measurements[0].value == 0' "$evidence_dir/metric-before-lag.json" \
  >/dev/null || die 'Risk CDC lag gauge is not zero before outage'

baseline_started_at_ms="$(current_epoch_millis)"
baseline_row="$(wait_for_metric_row zero "$baseline_started_at_ms" \
  'Risk CDC metric row did not become fresh before timeout')"
IFS='|' read -r baseline_lag baseline_updated <<<"$baseline_row"
[[ "$baseline_lag" == 0 ]] || die \
  "Risk CDC metric is not healthy before outage: lag=$baseline_lag"
baseline_age_ms="$(( $(current_epoch_millis) - baseline_updated ))"
(( baseline_age_ms >= 0 &&
  baseline_age_ms <= maximum_metric_age_seconds * 1000 )) || die \
  "Risk CDC baseline metric is stale or from the future: age_ms=$baseline_age_ms"
metric_json observation_updated_at_unix_ms \
  "$evidence_dir/metric-baseline-updated-at.json" at-least "$baseline_updated"
zero_traffic_row="$(wait_for_metric_row zero "$baseline_updated" \
  'Risk CDC metric did not remain fresh and zero without traffic')"
zero_traffic_updated="${zero_traffic_row#*|}"
zero_traffic_age_ms="$(( $(current_epoch_millis) - zero_traffic_updated ))"
(( zero_traffic_age_ms >= 0 &&
  zero_traffic_age_ms <= maximum_metric_age_seconds * 1000 )) || die \
  "Risk CDC zero-traffic metric is stale or from the future: age_ms=$zero_traffic_age_ms"
metric_json observation_updated_at_unix_ms \
  "$evidence_dir/metric-zero-traffic-updated-at.json" at-least "$zero_traffic_updated"
printf 'baseline_age_ms=%s\nzero_traffic_row=%s\nzero_traffic_age_ms=%s\n' \
  "$baseline_age_ms" "$zero_traffic_row" "$zero_traffic_age_ms" \
  >"$evidence_dir/metric-baseline-age.txt"

curl_with_deadline -X PUT \
  "$connector_base_url/connectors/risk-service-outbox/pause" >/dev/null
connector_paused=true
wait_for_connector_state risk-service-outbox PAUSED \
  >"$evidence_dir/connector-paused.json"

event_id="$(cat /proc/sys/kernel/random/uuid)"
[[ "$event_id" =~ ^[0-9a-f-]{36}$ ]] || die 'could not create a UUID event identity'

resolve_seeded_matching_command() {
  local partition candidate_key candidate_payload
  for partition in $(seq 0 14); do
    candidate_key="$(kns exec "$kafka_pod" -- \
      /opt/kafka/bin/kafka-console-consumer.sh \
      --bootstrap-server 127.0.0.1:9092 \
      --topic matching.commands --partition "$partition" --offset 0 --max-messages 1 \
      --timeout-ms 1000 \
      --formatter-property print.key=true \
      --formatter-property print.value=false 2>/dev/null | tr -d '\r\n\t' || true)"
    candidate_payload="$(kns exec "$kafka_pod" -- \
      /opt/kafka/bin/kafka-console-consumer.sh \
      --bootstrap-server 127.0.0.1:9092 \
      --topic matching.commands --partition "$partition" --offset 0 --max-messages 1 \
      --timeout-ms 1000 \
      --formatter-property print.key=false \
      --formatter-property print.value=true 2>/dev/null \
      | od -An -tx1 | tr -d ' \n\r' || true)"
    if [[ "$candidate_key" =~ ^[0-9a-fA-F-]{36}$ &&
        "$candidate_payload" =~ ^([0-9a-fA-F]{2})+0a$ ]]; then
      command_partition="$partition"
      command_key="$candidate_key"
      payload_hex="${candidate_payload%0a}"
      return 0
    fi
  done
  die 'could not resolve a valid seeded Matching command payload from any partition'
}

validate_selected_matching_open_barrier() {
  printf '%s' "$payload_hex" |
    "$matching_fixture_validator" --validate-open-barrier \
      "$matching_trading_day" "$matching_trading_session_id" \
      "$artifact_sha256" "$artifact_routing_version" "$matching_image_digest" \
      "$command_partition" "$command_key" >/dev/null || die \
        'selected retained Matching command is not the current Open Barrier'
}

resolve_seeded_matching_command
validate_selected_matching_open_barrier
created_at_ms="$(current_epoch_millis)"
aggregate_id="${run_id}-${event_id}"
headers_json="$(cdc_observer_headers_json "$event_id")" || die \
  'could not construct the CDC observer outbox headers'
headers_json_sql="$(printf '%s' "$headers_json" | sed "s/'/''/g")"
sql "INSERT INTO risk_service.outbox (
    event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
    aggregate_type, aggregate_id, created_at_unix_ms, created_at
  ) VALUES (
    '$event_id'::uuid, 'matching.commands', '$command_key', $command_partition,
    decode('$payload_hex', 'hex'),
    '$(cdc_observer_payload_type)', '$headers_json_sql', 'cdc_delivery_observer',
    '$aggregate_id', $created_at_ms,
    to_timestamp($created_at_ms / 1000.0) AT TIME ZONE 'UTC'
  );" >"$evidence_dir/outbox-insert.log"
jq -n --arg runId "$run_id" --arg eventId "$event_id" --arg topic matching.commands \
  --arg commandKey "$command_key" --argjson commandPartition "$command_partition" \
  --argjson createdAtUnixMs "$created_at_ms" \
  --arg tradingDay "$matching_trading_day" \
  --arg tradingSessionId "$matching_trading_session_id" \
  --arg artifactSha256 "$artifact_sha256" \
  --arg routingAlgorithmVersion "$artifact_routing_version" \
  --arg matchingImageDigest "$matching_image_digest" \
  --arg headersJson "$headers_json" \
  --arg validatorPath "$matching_fixture_validator" \
  --arg validatorSha256 "$matching_fixture_validator_sha256" \
  '{runId:$runId,eventId:$eventId,topic:$topic,commandKey:$commandKey,
    commandPartition:$commandPartition,
    createdAtUnixMs:$createdAtUnixMs,tradingDay:$tradingDay,
    tradingSessionId:$tradingSessionId,artifactSha256:$artifactSha256,
    routingAlgorithmVersion:$routingAlgorithmVersion,
    matchingImageDigest:$matchingImageDigest,
    headersJson:$headersJson,
    validatorPath:$validatorPath,validatorSha256:$validatorSha256,
    openBarrierValidated:true}' \
  >"$evidence_dir/event.json"

paused_row="$(wait_for_metric_row positive "$baseline_updated" \
  'Risk CDC metric did not expose pending outbox lag while connector was paused')"
paused_updated="${paused_row#*|}"
printf '%s\n' "$paused_row" >"$evidence_dir/metric-paused-row.txt"
metric_json connector_lag_events "$evidence_dir/metric-paused-lag.json" positive
metric_json outbox_age_millis "$evidence_dir/metric-paused-age.json" nonnegative
metric_json observation_updated_at_unix_ms \
  "$evidence_dir/metric-paused-updated-at.json" at-least "$paused_updated"
assert_metric_measurement_is_nonnegative "$evidence_dir/metric-paused-lag.json"
assert_metric_measurement_is_nonnegative "$evidence_dir/metric-paused-age.json"
jq -e '.measurements[0].value >= 1' "$evidence_dir/metric-paused-lag.json" \
  >/dev/null || die 'Risk CDC lag gauge did not increase during outage'

curl_with_deadline -X PUT \
  "$connector_base_url/connectors/risk-service-outbox/resume" >/dev/null
wait_for_connector_state risk-service-outbox RUNNING \
  >"$evidence_dir/connector-recovered.json"
connector_paused=false
capture_retained_connector_states "$evidence_dir/connectors-running-recovered.json" \
  "$evidence_dir/connector-recovered.json"
observation_row="$(wait_for_observation)"
recovered_row="$(wait_for_metric_row zero "$paused_updated" \
  'Risk CDC metric did not return to zero after connector recovery')"
recovered_updated="${recovered_row#*|}"
printf '%s\n' "$observation_row" >"$evidence_dir/observation-row.txt"
printf '%s\n' "$recovered_row" >"$evidence_dir/metric-recovered-row.txt"
metric_json connector_lag_events "$evidence_dir/metric-recovered-lag.json" zero
metric_json outbox_age_millis "$evidence_dir/metric-recovered-age.json" nonnegative
metric_json observation_updated_at_unix_ms \
  "$evidence_dir/metric-recovered-updated-at.json" at-least "$recovered_updated"
assert_metric_measurement_is_nonnegative "$evidence_dir/metric-recovered-lag.json"
assert_metric_measurement_is_nonnegative "$evidence_dir/metric-recovered-age.json"
jq -e '.measurements[0].value == 0' "$evidence_dir/metric-recovered-lag.json" \
  >/dev/null || die 'Risk CDC lag gauge did not recover to zero'

capture_active_workload_logs() {
  local workload pods output
  local -a workloads=(
    account-service
    risk-service
    persistence
    market-data-projection
    marketdata-streamer
    query-service
    quickfix-gateway
  )
  for workload in "${workloads[@]}"; do
    pods="$(kns get pods -l "app.kubernetes.io/name=$workload" -o name)" || die \
      "could not list pods for $workload"
    [[ -n "$pods" ]] || die "no ready workload pod exists for $workload"
    output="$evidence_dir/$workload.log"
    if ! kns logs -l "app.kubernetes.io/name=$workload" --all-containers=true \
        --prefix=true --tail=400 >"$output" 2>&1; then
      if ! resilience_log_is_safe "$output"; then
        printf '%s\n' \
          'active workload log omitted after the sensitive-log safety check failed' \
          >"$output"
      fi
      die "could not capture logs for $workload"
    fi
    if ! resilience_log_is_safe "$output"; then
      printf '%s\n' \
        'active workload log omitted after the sensitive-log safety check failed' \
        >"$output"
      die "$workload logs contain a prohibited secret or raw payload pattern"
    fi
  done
}

capture_active_workload_logs

jq -n \
  --arg status PASS --arg runId "$run_id" --arg eventId "$event_id" \
  --arg baseline "$baseline_row" --arg paused "$paused_row" --arg recovered "$recovered_row" \
  --arg observation "$observation_row" \
  '{status:$status,runId:$runId,eventId:$eventId,
    baselineMetric:$baseline,pausedMetric:$paused,recoveredMetric:$recovered,
    observation:$observation,
    assertions:[
      "Risk actuator liveness and readiness are reachable",
      "Risk Debezium connector/task reached PAUSED and then RUNNING",
      "pending matching.commands outbox raised durable lag while connector was paused",
      "exact event_id observation was persisted only after Kafka recovery",
      "durable matching.commands lag returned to zero with a newer timestamp",
      "Actuator lag and age gauges were read from one Risk pod and correlated with durable refresh timestamps",
      "baseline durable CDC metric was fresh and not from the future",
      "fresh zero-traffic matching.commands metric remained at zero",
      "all active Phase 1 workload logs passed the sensitive-log safety contract"
    ]}' >"$evidence_dir/verdict.json"

printf 'Risk CDC observer outage/recovery passed: event_id=%s\n' "$event_id"

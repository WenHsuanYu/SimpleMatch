#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-resilience.sh
source "$script_dir/lib/local-resilience.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"

namespace=""
expected_namespace_run_id=""
evidence_dir=""
timeout_seconds="${SIMPLEMATCH_CDC_OBSERVER_TIMEOUT_SECONDS:-180}"
maximum_metric_age_seconds=""
kind_cluster="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
kind_context="kind-${kind_cluster}"

connect_port_forward_pid=""
risk_port_forward_pid=""
connect_port=""
risk_port=""
connector_paused=false
postgres_pod=""
kafka_pod=""
event_id=""

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
(( timeout_seconds <= 600 )) || die '--timeout-seconds must not exceed 600'

for tool in kubectl jq curl date seq sleep od tr grep sed tail cat; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done

mkdir -p "$evidence_dir"
evidence_dir="$(cd -- "$evidence_dir" && pwd)"
run_id="cdc-observer-$(date -u +%Y%m%d-%H%M%S)-$$"
connector_base_url=""
risk_base_url=""
curl_options=(--connect-timeout 3 --max-time 10)
command_partition=""
command_key=""
payload_hex=""

kns() {
  kubectl --context "$kind_context" -n "$namespace" "$@"
}

collect_diagnostics() {
  kns get pods -o wide >"$evidence_dir/diagnostics-pods.txt" 2>&1 || true
  kns get deployments,statefulsets,jobs >"$evidence_dir/diagnostics-workloads.txt" 2>&1 || true
  kns logs -l app.kubernetes.io/name=risk-service --all-containers=true \
    --prefix=true --tail=300 >"$evidence_dir/diagnostics-risk.log" 2>&1 || true
  kns logs -l app.kubernetes.io/name=kafka-connect --all-containers=true \
    --prefix=true --tail=200 >"$evidence_dir/diagnostics-connect.log" 2>&1 || true
  if [[ -n "$connector_base_url" ]]; then
    curl "${curl_options[@]}" -fsS \
      "$connector_base_url/connectors/risk-service-outbox/status" \
      >"$evidence_dir/diagnostics-connector-status.json" 2>&1 || true
  fi
  if [[ -n "$risk_base_url" ]]; then
    curl "${curl_options[@]}" -fsS "$risk_base_url/actuator/health" \
      >"$evidence_dir/diagnostics-risk-health.json" 2>&1 || true
  fi
}

cleanup() {
  local exit_code="$?"
  if [[ "$connector_paused" == true && -n "$connector_base_url" ]]; then
    curl "${curl_options[@]}" -fsS -X PUT \
      "$connector_base_url/connectors/risk-service-outbox/resume" >/dev/null 2>&1 || true
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
    collect_diagnostics
    if [[ ! -f "$evidence_dir/verdict.json" ]]; then
      jq -n --arg status FAIL --arg runId "$run_id" \
        '{status:$status,runId:$runId,
          reason:"Risk CDC observer phase failed; inspect diagnostics"}' \
        >"$evidence_dir/verdict.json" || true
    fi
  fi
  trap - EXIT
  exit "$exit_code"
}
trap cleanup EXIT

current_context="$(kubectl config current-context)"
[[ "$current_context" == "$kind_context" ]] || die \
  "current Kubernetes context=$current_context, expected $kind_context"
kubectl --context "$kind_context" get namespace "$namespace" >/dev/null \
  || die "namespace does not exist: $namespace"
simplematch_kind_namespace_is_disposable \
  "$kind_context" "$namespace" local-production-like-certification || die \
  "namespace is not an owned disposable local certification namespace: $namespace"
namespace_run_id="$(kubectl --context "$kind_context" get namespace "$namespace" \
  -o jsonpath='{.metadata.labels.simplematch\.io/run-id}')" || die \
  "could not read certification namespace ownership: $namespace"
[[ -n "$namespace_run_id" ]] || die \
  "certification namespace has no non-empty run-id label: $namespace"
[[ "$namespace_run_id" == "$expected_namespace_run_id" ]] || die \
  "certification namespace run-id does not match the requested run: $namespace"
maximum_metric_age_seconds="$(kns get configmap risk-service-config -o json |
  jq -er '.data["application.yaml"] | capture("maximum-metric-age: (?<seconds>[0-9]+)s").seconds')" || die \
  'could not read the deployed Risk maximum-metric-age configuration'
[[ "$maximum_metric_age_seconds" =~ ^[1-9][0-9]*$ ]] || die \
  'deployed Risk maximum-metric-age must be a positive integer number of seconds'
(( maximum_metric_age_seconds <= 600 )) || die \
  'deployed Risk maximum-metric-age must not exceed 600 seconds'

start_port_forward() {
  local target="$1" remote_port="$2" log_path="$3" output
  kns port-forward "$target" ":$remote_port" >"$log_path" 2>&1 &
  PORT_FORWARD_PID="$!"
  PORT_FORWARD_PORT=""
  for _ in $(seq 1 60); do
    kill -0 "$PORT_FORWARD_PID" >/dev/null 2>&1 || {
      cat "$log_path" >&2
      die "port-forward exited for $target"
    }
    output="$(sed -nE 's/.*127\.0\.0\.1:([0-9]+) -> [0-9]+.*/\1/p' "$log_path" | tail -n 1)"
    if [[ "$output" =~ ^[0-9]+$ ]]; then
      PORT_FORWARD_PORT="$output"
      return 0
    fi
    sleep 1
  done
  die "could not resolve port-forward for $target"
}

wait_for_connector_state() {
  local connector="$1" expected="$2" output deadline
  [[ -n "$connector" && -n "$expected" ]] || die \
    'connector name and expected state are required'
  deadline=$(( $(date +%s) + timeout_seconds ))
  while :; do
    output="$(curl "${curl_options[@]}" -fsS \
      "$connector_base_url/connectors/$connector/status" \
      2>/dev/null || true)"
    if jq -e --arg expected "$expected" '
        .connector.state == $expected and
        (.tasks | length) > 0 and ([.tasks[].state] | all(. == $expected))
      ' >/dev/null 2>&1 <<<"$output"; then
      printf '%s\n' "$output"
      return 0
    fi
    (( $(date +%s) < deadline )) || die \
      "$connector did not become $expected before timeout"
    sleep 1
  done
}

capture_retained_connector_states() {
  local output="$1" risk_status account_status marketdata_status
  risk_status="$(wait_for_connector_state risk-service-outbox RUNNING)"
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
  local output lag updated deadline lag_matches
  case "$expected_lag" in
    zero|positive) ;;
    *) die "unsupported CDC metric lag expectation: $expected_lag" ;;
  esac
  deadline=$(( $(date +%s) + timeout_seconds ))
  while :; do
    output="$(read_metric_row 2>/dev/null || true)"
    IFS='|' read -r lag updated <<<"$output"
    lag_matches=false
    case "$expected_lag" in
      any) lag_matches=true ;;
      zero) [[ "$lag" == 0 ]] && lag_matches=true ;;
      positive) [[ "$lag" =~ ^[1-9][0-9]*$ ]] && lag_matches=true ;;
    esac
    if [[ "$updated" =~ ^[1-9][0-9]*$ && "$updated" -gt "$minimum_updated_at" &&
      "$lag_matches" == true ]]; then
      printf '%s\n' "$output"
      return 0
    fi
    (( $(date +%s) < deadline )) || die "$failure_message"
    sleep 2
  done
}

metric_json() {
  local metric="$1" output="$2" metric_url response deadline
  metric_url="$risk_base_url/actuator/metrics/simplematch.delivery.observations"
  metric_url+="?tag=component:risk-cdc-delivery&tag=metric:${metric}"
  deadline=$(( $(date +%s) + timeout_seconds ))
  while :; do
    response="$(curl "${curl_options[@]}" -fsS "$metric_url" 2>/dev/null || true)"
    if jq -e '.name == "simplematch.delivery.observations"
        and (.measurements | length) > 0' >/dev/null 2>&1 <<<"$response"; then
      printf '%s\n' "$response" >"$output"
      return 0
    fi
    (( $(date +%s) < deadline )) || die "Actuator metric is missing for $metric"
    sleep 2
  done
}

wait_for_observation() {
  local output deadline
  deadline=$(( $(date +%s) + timeout_seconds ))
  while :; do
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
    (( $(date +%s) < deadline )) || die \
      'Risk CDC observation row did not appear after connector recovery'
    sleep 2
  done
}

kns rollout status deployment/risk-service --timeout=300s >/dev/null
kns rollout status deployment/kafka-connect --timeout=300s >/dev/null
kns rollout status statefulset/postgres --timeout=300s >/dev/null
postgres_pod="$(kns get pods -l app.kubernetes.io/name=postgres \
  -o jsonpath='{.items[0].metadata.name}')"
kafka_pod="$(kns get pods -l app.kubernetes.io/name=kafka \
  -o jsonpath='{.items[0].metadata.name}')"
[[ -n "$postgres_pod" && -n "$kafka_pod" ]] || die 'Risk CDC observer prerequisites are missing'

start_port_forward service/kafka-connect 8083 "$evidence_dir/connect-port-forward.log"
connect_port_forward_pid="$PORT_FORWARD_PID"
connect_port="$PORT_FORWARD_PORT"
connector_base_url="http://127.0.0.1:${connect_port}"
wait_for_connector_state risk-service-outbox RUNNING \
  >"$evidence_dir/connector-running-before.json"
capture_retained_connector_states "$evidence_dir/connectors-running-before.json"

start_port_forward service/risk-service 8080 "$evidence_dir/risk-port-forward.log"
risk_port_forward_pid="$PORT_FORWARD_PID"
risk_port="$PORT_FORWARD_PORT"
risk_base_url="http://127.0.0.1:${risk_port}"
curl "${curl_options[@]}" -fsS "$risk_base_url/actuator/health/liveness" \
  >"$evidence_dir/health-liveness.json"
curl "${curl_options[@]}" -fsS "$risk_base_url/actuator/health/readiness" \
  >"$evidence_dir/health-readiness.json"
metric_json connector_lag_events "$evidence_dir/metric-before-lag.json"
metric_json outbox_age_millis "$evidence_dir/metric-before-age.json"
jq -e '.measurements[0].value == 0' "$evidence_dir/metric-before-lag.json" \
  >/dev/null || die 'Risk CDC lag gauge is not zero before outage'

baseline_started_at_ms="$(( $(date +%s) * 1000 ))"
baseline_row="$(wait_for_metric_row zero "$baseline_started_at_ms" \
  'Risk CDC metric row did not become fresh before timeout')"
IFS='|' read -r baseline_lag baseline_updated <<<"$baseline_row"
[[ "$baseline_lag" == 0 ]] || die \
  "Risk CDC metric is not healthy before outage: lag=$baseline_lag"
baseline_age_ms="$(( $(date +%s) * 1000 - baseline_updated ))"
(( baseline_age_ms >= 0 &&
  baseline_age_ms <= maximum_metric_age_seconds * 1000 )) || die \
  "Risk CDC baseline metric is stale or from the future: age_ms=$baseline_age_ms"
printf '%s\n' "$baseline_age_ms" >"$evidence_dir/metric-baseline-age.txt"

curl "${curl_options[@]}" -fsS -X PUT \
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

resolve_seeded_matching_command
created_at_ms="$(( $(date +%s) * 1000 ))"
aggregate_id="${run_id}-${event_id}"
sql "INSERT INTO risk_service.outbox (
    event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
    aggregate_type, aggregate_id, created_at_unix_ms, created_at
  ) VALUES (
    '$event_id'::uuid, 'matching.commands', '$command_key', $command_partition,
    decode('$payload_hex', 'hex'),
    'simplematch.matching.runtime.v1.MatchingCommand', '{}', 'cdc_delivery_observer',
    '$aggregate_id', $created_at_ms,
    to_timestamp($created_at_ms / 1000.0) AT TIME ZONE 'UTC'
  );" >"$evidence_dir/outbox-insert.log"
jq -n --arg runId "$run_id" --arg eventId "$event_id" --arg topic matching.commands \
  --arg commandKey "$command_key" --argjson commandPartition "$command_partition" \
  --argjson createdAtUnixMs "$created_at_ms" \
  '{runId:$runId,eventId:$eventId,topic:$topic,commandKey:$commandKey,
    commandPartition:$commandPartition,
    createdAtUnixMs:$createdAtUnixMs}' \
  >"$evidence_dir/event.json"

paused_row="$(wait_for_metric_row positive "$baseline_updated" \
  'Risk CDC metric did not expose pending outbox lag while connector was paused')"
paused_updated="${paused_row#*|}"
printf '%s\n' "$paused_row" >"$evidence_dir/metric-paused-row.txt"
metric_json connector_lag_events "$evidence_dir/metric-paused-lag.json"
metric_json outbox_age_millis "$evidence_dir/metric-paused-age.json"
jq -e '.measurements[0].value >= 1' "$evidence_dir/metric-paused-lag.json" \
  >/dev/null || die 'Risk CDC lag gauge did not increase during outage'

curl "${curl_options[@]}" -fsS -X PUT \
  "$connector_base_url/connectors/risk-service-outbox/resume" >/dev/null
connector_paused=false
wait_for_connector_state risk-service-outbox RUNNING \
  >"$evidence_dir/connector-recovered.json"
capture_retained_connector_states "$evidence_dir/connectors-running-recovered.json"
observation_row="$(wait_for_observation)"
recovered_row="$(wait_for_metric_row zero "$paused_updated" \
  'Risk CDC metric did not return to zero after connector recovery')"
printf '%s\n' "$observation_row" >"$evidence_dir/observation-row.txt"
printf '%s\n' "$recovered_row" >"$evidence_dir/metric-recovered-row.txt"
metric_json connector_lag_events "$evidence_dir/metric-recovered-lag.json"
metric_json outbox_age_millis "$evidence_dir/metric-recovered-age.json"
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
    kns logs -l "app.kubernetes.io/name=$workload" --all-containers=true \
      --prefix=true --tail=400 >"$output" 2>&1 || die \
      "could not capture logs for $workload"
    resilience_log_is_safe "$output" || die \
      "$workload logs contain a prohibited secret or raw payload pattern"
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
      "baseline durable CDC metric was fresh and not from the future",
      "all active Phase 1 workload logs passed the sensitive-log safety contract"
    ]}' >"$evidence_dir/verdict.json"

printf 'Risk CDC observer outage/recovery passed: event_id=%s\n' "$event_id"

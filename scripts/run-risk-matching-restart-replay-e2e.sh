#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

namespace=""
baseline_evidence_dir=""
evidence_dir=""
timeout_seconds="${SIMPLEMATCH_RM1_REPLAY_TIMEOUT_SECONDS:-120}"

job_name="risk-matching-replay-verifier"
run_config_name="risk-matching-replay-run"
job_manifest="$repo_root/deploy/k8s/verification/risk-matching-replay-verifier-job.yaml"
verifier_image="simplematch/risk-matching-e2e-verifier:local"
port_forward_pid=""
job_created=false
run_config_created=false
helper_pod=""

usage() {
  cat <<'EOF'
Usage:
  scripts/run-risk-matching-restart-replay-e2e.sh \
    --namespace NAME \
    --baseline-evidence-dir PATH \
    --evidence-dir PATH \
    [--timeout-seconds N]

Completes the retained RM-1 restart/replay scenario from a previously passing deployed E2E run.
The script:
  1. verifies the baseline Admission/outbox/Kafka evidence is still authoritative;
  2. restarts Risk Service and Kafka Connect and proves their Pods were replaced;
  3. requires the risk-service-outbox connector to recover to RUNNING;
  4. resubmits the same durably-equivalent command through the replay verifier;
  5. requires synchronous terminal ACCEPTED without a new outbox or matching.commands record.

The script never deletes or rewrites Kafka Connect offsets and never mutates Risk database rows.
EOF
}

die() {
  printf 'RM-1 restart/replay: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) namespace="${2:?--namespace requires a value}"; shift 2 ;;
    --baseline-evidence-dir) baseline_evidence_dir="${2:?--baseline-evidence-dir requires a value}"; shift 2 ;;
    --evidence-dir) evidence_dir="${2:?--evidence-dir requires a value}"; shift 2 ;;
    --timeout-seconds) timeout_seconds="${2:?--timeout-seconds requires a value}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

[[ -n "$namespace" ]] || { usage >&2; die '--namespace is required'; }
[[ -n "$baseline_evidence_dir" ]] || { usage >&2; die '--baseline-evidence-dir is required'; }
[[ -n "$evidence_dir" ]] || { usage >&2; die '--evidence-dir is required'; }
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die '--timeout-seconds must be a positive integer'
(( timeout_seconds <= 300 )) || die '--timeout-seconds must not exceed 300'

for tool in kubectl jq curl sed tail seq sleep cmp awk date; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
[[ -f "$job_manifest" ]] || die "replay verifier Job manifest does not exist: $job_manifest"
[[ -d "$baseline_evidence_dir" ]] || die "baseline evidence directory does not exist: $baseline_evidence_dir"
baseline_evidence_dir="$(cd -- "$baseline_evidence_dir" && pwd)"

mkdir -p "$evidence_dir"
evidence_dir="$(cd -- "$evidence_dir" && pwd)"
shopt -s nullglob dotglob
existing_evidence=("$evidence_dir"/*)
shopt -u nullglob dotglob
((${#existing_evidence[@]} == 0)) || die \
  "evidence directory must be empty before verification: $evidence_dir"
mkdir -p "$evidence_dir/verifier"

required_baseline=(
  run-metadata.json
  request.json
  selected-instrument.json
  admission-outcome.json
  matching-command-record.json
  matching-command-decoded.json
  risk-admission.json
  risk-outbox.json
  verifier-verdict.json
  verdict.json
)
for file in "${required_baseline[@]}"; do
  [[ -s "$baseline_evidence_dir/$file" ]] || die "baseline evidence is missing $file"
done
jq -e '.status == "PASS"' "$baseline_evidence_dir/verdict.json" >/dev/null \
  || die 'baseline outer verdict is not PASS'
jq -e '.status == "PASS"' "$baseline_evidence_dir/verifier-verdict.json" >/dev/null \
  || die 'baseline typed verifier verdict is not PASS'
jq -e '.terminalStatus == "ACCEPTED"' "$baseline_evidence_dir/admission-outcome.json" >/dev/null \
  || die 'baseline Admission outcome is not terminal ACCEPTED'

baseline_namespace="$(jq -r '.namespace' "$baseline_evidence_dir/run-metadata.json")"
[[ "$baseline_namespace" == "$namespace" ]] || die \
  "baseline namespace=$baseline_namespace does not match requested namespace=$namespace"
run_id="$(jq -r '.runId' "$baseline_evidence_dir/run-metadata.json")"
trading_day="$(jq -r '.tradingDay' "$baseline_evidence_dir/run-metadata.json")"
account_id="$(jq -r '.accountId' "$baseline_evidence_dir/run-metadata.json")"
command_id="$(jq -r '.commandId' "$baseline_evidence_dir/request.json")"
order_id="$(jq -r '.orderId' "$baseline_evidence_dir/request.json")"
expected_partition="$(jq -r '.expectedPartition' "$baseline_evidence_dir/selected-instrument.json")"
expected_payload="$(jq -r '.payloadBase64' "$baseline_evidence_dir/risk-outbox.json")"
[[ -n "$run_id" && "$run_id" != null ]] || die 'baseline runId is missing'
[[ -n "$account_id" && "$account_id" != null ]] || die 'baseline accountId is missing'
[[ "$command_id" =~ ^[0-9a-fA-F-]{36}$ ]] || die 'baseline commandId is malformed'
[[ "$order_id" =~ ^[0-9a-fA-F-]{36}$ ]] || die 'baseline orderId is malformed'
[[ "$expected_partition" =~ ^[0-9]+$ ]] || die 'baseline partition is malformed'
[[ -n "$expected_payload" && "$expected_payload" != null ]] || die 'baseline outbox payload is missing'

collect_diagnostics() {
  kubectl -n "$namespace" get pods -o wide >"$evidence_dir/diagnostics-pods.txt" 2>&1 || true
  kubectl -n "$namespace" get deployments,statefulsets,jobs \
    >"$evidence_dir/diagnostics-workloads.txt" 2>&1 || true
  kubectl -n "$namespace" logs -l app.kubernetes.io/name=risk-service \
    --all-containers=true --prefix=true --tail=250 \
    >"$evidence_dir/diagnostics-risk.log" 2>&1 || true
  kubectl -n "$namespace" logs -l app.kubernetes.io/name=kafka-connect \
    --all-containers=true --prefix=true --tail=250 \
    >"$evidence_dir/diagnostics-kafka-connect.log" 2>&1 || true
  if [[ "$job_created" == true ]]; then
    kubectl -n "$namespace" describe job "$job_name" \
      >"$evidence_dir/diagnostics-replay-job.txt" 2>&1 || true
  fi
}

cleanup() {
  exit_code="$?"
  if [[ -n "$port_forward_pid" ]]; then
    kill "$port_forward_pid" >/dev/null 2>&1 || true
    wait "$port_forward_pid" >/dev/null 2>&1 || true
  fi
  if [[ "$exit_code" -ne 0 ]]; then
    collect_diagnostics
    if [[ ! -f "$evidence_dir/verdict.json" ]]; then
      jq -n --arg status FAIL --arg commandId "$command_id" \
        --arg reason 'RM-1 restart/replay verifier failed; inspect diagnostics' \
        '{status:$status, commandId:$commandId, reason:$reason}' \
        >"$evidence_dir/verdict.json" || true
    fi
  fi
  if [[ "$job_created" == true ]]; then
    kubectl -n "$namespace" delete job "$job_name" --ignore-not-found --wait=false \
      >/dev/null 2>&1 || true
  fi
  if [[ "$run_config_created" == true ]]; then
    kubectl -n "$namespace" delete configmap "$run_config_name" --ignore-not-found --wait=false \
      >/dev/null 2>&1 || true
  fi
  trap - EXIT
  exit "$exit_code"
}
trap cleanup EXIT

kind_cluster="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
expected_context="kind-${kind_cluster}"
current_context="$(kubectl config current-context)"
[[ "$current_context" == "$expected_context" ]] || die \
  "current Kubernetes context=$current_context, expected canonical $expected_context"
kubectl get namespace "$namespace" >/dev/null 2>&1 || die "namespace does not exist: $namespace"
if kubectl -n "$namespace" get job "$job_name" >/dev/null 2>&1 \
    || kubectl -n "$namespace" get configmap "$run_config_name" >/dev/null 2>&1; then
  die "replay helper resources already exist; inspect or delete job/$job_name and configmap/$run_config_name"
fi

kubectl -n "$namespace" rollout status deployment/risk-service --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status deployment/kafka-connect --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status statefulset/postgres --timeout=300s >/dev/null
kubectl -n "$namespace" wait --for=jsonpath='{.status.readyReplicas}'=3 statefulset/kafka --timeout=300s >/dev/null

artifact_day="$(kubectl -n "$namespace" get configmap matching-session-config -o jsonpath='{.data.trading_day}')"
[[ "$artifact_day" == "$trading_day" ]] || die \
  "retained namespace trading_day=$artifact_day differs from baseline $trading_day"

postgres_pod="$(kubectl -n "$namespace" get pods -l app.kubernetes.io/name=postgres -o jsonpath='{.items[0].metadata.name}')"
kafka_pod="$(kubectl -n "$namespace" get pods -l 'app.kubernetes.io/name=kafka,app.kubernetes.io/component=broker' -o jsonpath='{.items[0].metadata.name}')"
[[ -n "$postgres_pod" ]] || die 'cannot resolve PostgreSQL Pod'
[[ -n "$kafka_pod" ]] || die 'cannot resolve Kafka Pod'

capture_admission() {
  kubectl -n "$namespace" exec "$postgres_pod" -- \
    psql -U simplematch -d simplematch -At -v ON_ERROR_STOP=1 -c "
      SELECT json_build_object(
        'commandId', command_id::text,
        'orderId', order_id::text,
        'accountId', account_id::text,
        'state', state,
        'routingPartition', routing_partition,
        'artifactTradingDay', artifact_trading_day::text,
        'artifactContentSha256', artifact_content_sha256,
        'routingAlgorithmVersion', routing_algorithm_version
      )::text
      FROM risk_service.admission_journal
      WHERE command_id = '$command_id'::uuid;
    "
}

capture_outbox() {
  kubectl -n "$namespace" exec "$postgres_pod" -- \
    psql -U simplematch -d simplematch -At -v ON_ERROR_STOP=1 -c "
      SELECT json_build_object(
        'eventId', event_id::text,
        'topic', topic,
        'messageKey', message_key,
        'partition', kafka_partition_id,
        'payloadType', payload_type,
        'payloadBase64', replace(encode(payload, 'base64'), E'\\n', ''),
        'aggregateType', aggregate_type,
        'aggregateId', aggregate_id
      )::text
      FROM risk_service.outbox
      WHERE topic = 'matching.commands'
        AND message_key = '$command_id'
      ORDER BY id ASC
      LIMIT 1;
    "
}

capture_outbox_count() {
  kubectl -n "$namespace" exec "$postgres_pod" -- \
    psql -U simplematch -d simplematch -At -v ON_ERROR_STOP=1 -c "
      SELECT COUNT(*)
      FROM risk_service.outbox
      WHERE topic = 'matching.commands'
        AND message_key = '$command_id';
    " | tr -d '[:space:]'
}

capture_offsets() {
  local destination="$1"
  local raw
  raw="$(kubectl -n "$namespace" exec "$kafka_pod" -- \
    /opt/kafka/bin/kafka-get-offsets.sh \
      --bootstrap-server kafka:9092 \
      --topic matching.commands)"
  jq -Rn --arg topic matching.commands '
    [inputs
      | select(length > 0)
      | split(":")
      | {partition:(.[1] | tonumber), offset:(.[2] | tonumber)}]
    | sort_by(.partition)
    | {topic:$topic,
       endOffsets:(map({key:(.partition | tostring), value:.offset}) | from_entries)}
  ' <<<"$raw" >"$destination"
  [[ "$(jq '.endOffsets | length' "$destination")" -eq 15 ]] || die \
    "Kafka offset snapshot does not cover all 15 matching.commands partitions: $destination"
}

capture_pod_uids() {
  local selector="$1"
  local destination="$2"
  kubectl -n "$namespace" get pods -l "$selector" -o json \
    | jq '[.items[].metadata.uid] | sort' >"$destination"
  [[ "$(jq 'length' "$destination")" -gt 0 ]] || die "selector has no Pods: $selector"
}

require_all_pods_replaced() {
  local before="$1"
  local after="$2"
  jq -e -n --slurpfile before "$before" --slurpfile after "$after" '
    ($before[0] | length) > 0
    and ($after[0] | length) > 0
    and ($before[0] | all(. as $uid | ($after[0] | index($uid) | not)))
  ' >/dev/null || die "restart did not replace every Pod recorded in $before"
}

require_json_equal() {
  local left="$1"
  local right="$2"
  local description="$3"
  if ! diff -u <(jq -S . "$left") <(jq -S . "$right") >/dev/null; then
    diff -u <(jq -S . "$left") <(jq -S . "$right") >&2 || true
    die "$description"
  fi
}

start_connect_port_forward() {
  if [[ -n "$port_forward_pid" ]]; then
    kill "$port_forward_pid" >/dev/null 2>&1 || true
    wait "$port_forward_pid" >/dev/null 2>&1 || true
  fi
  local log_path="$evidence_dir/kafka-connect-port-forward.log"
  : >"$log_path"
  kubectl -n "$namespace" port-forward service/kafka-connect :8083 >"$log_path" 2>&1 &
  port_forward_pid="$!"
  connect_port=""
  for _ in $(seq 1 60); do
    if ! kill -0 "$port_forward_pid" >/dev/null 2>&1; then
      cat "$log_path" >&2
      die 'Kafka Connect port-forward exited before becoming ready'
    fi
    connect_port="$(sed -nE 's/.*127\.0\.0\.1:([0-9]+) -> 8083.*/\1/p' "$log_path" | tail -n 1)"
    [[ -n "$connect_port" ]] && return 0
    sleep 1
  done
  die 'could not resolve Kafka Connect port-forward port'
}

capture_connector_status() {
  local destination="$1"
  local url="http://127.0.0.1:${connect_port}/connectors/risk-service-outbox/status"
  printf '%s\n' '{}' >"$destination"
  for _ in $(seq 1 90); do
    if curl -fsS "$url" >"$destination" 2>/dev/null \
        && jq -e '.connector.state == "RUNNING"
          and (.tasks | length > 0)
          and ([.tasks[].state] | all(. == "RUNNING"))' "$destination" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  cat "$destination" >&2 || true
  die 'risk-service-outbox connector/task did not recover to RUNNING'
}

current_admission="$(capture_admission)"
[[ -n "$current_admission" ]] || die 'baseline Risk admission no longer exists in retained namespace'
printf '%s\n' "$current_admission" | jq . >"$evidence_dir/risk-admission-before-restart.json"
current_outbox="$(capture_outbox)"
[[ -n "$current_outbox" ]] || die 'baseline Risk outbox no longer exists in retained namespace'
printf '%s\n' "$current_outbox" | jq . >"$evidence_dir/risk-outbox-before-restart.json"
[[ "$(capture_outbox_count)" == 1 ]] || die 'baseline command no longer has exactly one Risk outbox row'
require_json_equal "$baseline_evidence_dir/risk-admission.json" \
  "$evidence_dir/risk-admission-before-restart.json" \
  'retained Risk admission drifted since the passing baseline run'
require_json_equal "$baseline_evidence_dir/risk-outbox.json" \
  "$evidence_dir/risk-outbox-before-restart.json" \
  'retained Risk outbox drifted since the passing baseline run'

capture_offsets "$evidence_dir/matching-offsets-before-restart.json"
capture_pod_uids 'app.kubernetes.io/name=risk-service' "$evidence_dir/risk-pod-uids-before.json"
capture_pod_uids 'app.kubernetes.io/name=kafka-connect' "$evidence_dir/kafka-connect-pod-uids-before.json"

start_connect_port_forward
capture_connector_status "$evidence_dir/connector-status-before-restart.json"
kill "$port_forward_pid" >/dev/null 2>&1 || true
wait "$port_forward_pid" >/dev/null 2>&1 || true
port_forward_pid=""

kubectl -n "$namespace" rollout restart deployment/risk-service deployment/kafka-connect >/dev/null
kubectl -n "$namespace" rollout status deployment/risk-service --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status deployment/kafka-connect --timeout=300s >/dev/null

capture_pod_uids 'app.kubernetes.io/name=risk-service' "$evidence_dir/risk-pod-uids-after.json"
capture_pod_uids 'app.kubernetes.io/name=kafka-connect' "$evidence_dir/kafka-connect-pod-uids-after.json"
require_all_pods_replaced "$evidence_dir/risk-pod-uids-before.json" "$evidence_dir/risk-pod-uids-after.json"
require_all_pods_replaced "$evidence_dir/kafka-connect-pod-uids-before.json" \
  "$evidence_dir/kafka-connect-pod-uids-after.json"

start_connect_port_forward
capture_connector_status "$evidence_dir/connector-status-after-restart.json"
capture_offsets "$evidence_dir/matching-offsets-after-restart.json"
require_json_equal "$evidence_dir/matching-offsets-before-restart.json" \
  "$evidence_dir/matching-offsets-after-restart.json" \
  'controlled Risk/Kafka Connect restart unexpectedly advanced matching.commands offsets'

kubectl -n "$namespace" create configmap "$run_config_name" \
  --from-literal="SIMPLEMATCH_RM1_TRADING_DAY=$trading_day" \
  --from-literal="SIMPLEMATCH_RM1_ACCOUNT_ID=$account_id" \
  --from-literal="SIMPLEMATCH_RM1_RUN_ID=$run_id" \
  --from-literal="SIMPLEMATCH_RM1_TIMEOUT_SECONDS=$timeout_seconds" \
  --dry-run=client -o json \
  | jq '.immutable = true
      | .metadata.labels = {
          "app.kubernetes.io/name":"risk-matching-replay-verifier",
          "app.kubernetes.io/component":"verification",
          "app.kubernetes.io/part-of":"simplematch"
        }' \
  | kubectl -n "$namespace" create -f - >/dev/null
run_config_created=true
kubectl -n "$namespace" get configmap "$run_config_name" -o json \
  >"$evidence_dir/verifier-run-config.json"

kubectl -n "$namespace" create -f "$job_manifest" >/dev/null
job_created=true
for _ in $(seq 1 60); do
  helper_pod="$(kubectl -n "$namespace" get pods -l "job-name=$job_name" -o json 2>/dev/null \
    | jq -r '.items[0].metadata.name // empty')"
  [[ -n "$helper_pod" ]] && break
  sleep 1
done
[[ -n "$helper_pod" ]] || die 'replay verifier Job did not create a Pod'

handoff_deadline_epoch="$(( $(date +%s) + timeout_seconds + 120 ))"
while true; do
  if kubectl -n "$namespace" exec "$helper_pod" -- test -f /tmp/evidence/.ready >/dev/null 2>&1; then
    break
  fi
  phase="$(kubectl -n "$namespace" get pod "$helper_pod" -o jsonpath='{.status.phase}')"
  if [[ "$phase" == Failed || "$phase" == Succeeded ]]; then
    kubectl -n "$namespace" logs "$helper_pod" --all-containers=true >&2 || true
    die "replay verifier Pod became terminal before evidence hand-off (phase=$phase)"
  fi
  (( $(date +%s) < handoff_deadline_epoch )) || die 'replay verifier evidence hand-off timed out'
  sleep 1
done

kubectl -n "$namespace" logs "$helper_pod" --all-containers=true \
  >"$evidence_dir/verifier.log" 2>&1 || true
kubectl -n "$namespace" cp "$helper_pod:/tmp/evidence/." "$evidence_dir/verifier" >/dev/null
[[ -f "$evidence_dir/verifier/.exit-code" ]] || die 'replay verifier evidence is missing .exit-code'
verifier_exit_code="$(tr -d '[:space:]' <"$evidence_dir/verifier/.exit-code")"
[[ "$verifier_exit_code" == 0 ]] || die "replay typed verifier exited with status $verifier_exit_code"
kubectl -n "$namespace" exec "$helper_pod" -- touch /tmp/evidence/.collected
kubectl -n "$namespace" wait --for=condition=complete "job/$job_name" --timeout=60s >/dev/null \
  || die 'replay verifier Job did not complete after evidence acknowledgement'

jq -e '.status == "PASS" and .mode == "REPLAY"
  and .admissionPath == "SYNCHRONOUS_ACCEPTED"
  and .terminalStatus == "ACCEPTED"' \
  "$evidence_dir/verifier/verifier-verdict.json" >/dev/null \
  || die 'replay typed verifier did not prove synchronous terminal ACCEPTED'
[[ "$(jq -r '.commandId' "$evidence_dir/verifier/request.json")" == "$command_id" ]] \
  || die 'replay request commandId differs from baseline'
require_json_equal "$baseline_evidence_dir/request.json" "$evidence_dir/verifier/request.json" \
  'replay durable request facts differ from the passing baseline request'

post_admission="$(capture_admission)"
printf '%s\n' "$post_admission" | jq . >"$evidence_dir/risk-admission-after-replay.json"
post_outbox="$(capture_outbox)"
printf '%s\n' "$post_outbox" | jq . >"$evidence_dir/risk-outbox-after-replay.json"
[[ "$(capture_outbox_count)" == 1 ]] || die 'equivalent replay created an additional Risk outbox row'
require_json_equal "$evidence_dir/risk-admission-before-restart.json" \
  "$evidence_dir/risk-admission-after-replay.json" \
  'equivalent replay changed the durable Risk Admission identity/route'
require_json_equal "$evidence_dir/risk-outbox-before-restart.json" \
  "$evidence_dir/risk-outbox-after-replay.json" \
  'equivalent replay changed the durable MatchingCommand outbox bytes or routing metadata'
[[ "$(jq -r '.payloadBase64' "$evidence_dir/risk-outbox-after-replay.json")" == "$expected_payload" ]] \
  || die 'replay outbox payload differs from baseline exact bytes'
[[ "$(jq -r '.partition' "$evidence_dir/risk-outbox-after-replay.json")" == "$expected_partition" ]] \
  || die 'replay outbox partition differs from baseline partition'

capture_offsets "$evidence_dir/matching-offsets-after-replay.json"
require_json_equal "$evidence_dir/matching-offsets-after-restart.json" \
  "$evidence_dir/matching-offsets-after-replay.json" \
  'equivalent terminal replay unexpectedly appended another matching.commands record'

jq -n \
  --arg status PASS \
  --arg namespace "$namespace" \
  --arg commandId "$command_id" \
  --arg orderId "$order_id" \
  --arg runId "$run_id" \
  --argjson partition "$expected_partition" \
  --arg payloadBase64 "$expected_payload" \
  '{
    status:$status,
    scenario:"RM1_RESTART_EQUIVALENT_REPLAY",
    namespace:$namespace,
    runId:$runId,
    commandId:$commandId,
    orderId:$orderId,
    partition:$partition,
    payloadBase64:$payloadBase64,
    proven:[
      "risk-service pods replaced",
      "kafka-connect pods replaced",
      "risk-service-outbox connector recovered RUNNING",
      "matching.commands offsets stable across controlled restart",
      "same durable command replay returned synchronous ACCEPTED",
      "admission identity/artifact/partition unchanged",
      "exact outbox MatchingCommand bytes unchanged",
      "exactly one matching.commands outbox row retained",
      "matching.commands offsets unchanged after terminal replay"
    ]
  }' >"$evidence_dir/verdict.json"

printf 'RM-1 restart/replay verification passed: command_id=%s partition=%s\n' \
  "$command_id" "$expected_partition"

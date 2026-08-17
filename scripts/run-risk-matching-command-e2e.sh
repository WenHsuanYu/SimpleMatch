#!/usr/bin/env bash
set -euo pipefail

# RM-1 deployed verification intentionally reuses an already-running local production-like
# namespace. The script owns only a run-scoped Account limit fixture and one ephemeral verifier Pod;
# it never creates/deletes the kind cluster or the application namespace.
namespace=""
trading_day=""
evidence_dir=""
image="${SIMPLEMATCH_RM1_VERIFIER_IMAGE:-simplematch/flyway-runner:local}"
timeout_seconds="${SIMPLEMATCH_RM1_VERIFIER_TIMEOUT_SECONDS:-90}"
keep_helper=false

usage() {
  cat <<'EOF'
Usage:
  scripts/run-risk-matching-command-e2e.sh \
    --namespace NAME \
    --trading-day YYYY-MM-DD \
    --evidence-dir PATH \
    [--image IMAGE] \
    [--timeout-seconds N] \
    [--keep-helper]

Proves the deployed RM-1 path:
  Risk gRPC -> Risk admission/outbox -> Debezium -> matching.commands.

The namespace must already have been created by the local production-like certification workflow.
The script does not create the kind cluster and does not delete application resources.
EOF
}

die() {
  printf 'RM-1 E2E: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) namespace="${2:?--namespace requires a value}"; shift 2 ;;
    --trading-day) trading_day="${2:?--trading-day requires a value}"; shift 2 ;;
    --evidence-dir) evidence_dir="${2:?--evidence-dir requires a value}"; shift 2 ;;
    --image) image="${2:?--image requires a value}"; shift 2 ;;
    --timeout-seconds) timeout_seconds="${2:?--timeout-seconds requires a value}"; shift 2 ;;
    --keep-helper) keep_helper=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

[[ -n "$namespace" ]] || { usage >&2; die '--namespace is required'; }
[[ "$trading_day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die \
  '--trading-day must use YYYY-MM-DD'
[[ -n "$evidence_dir" ]] || { usage >&2; die '--evidence-dir is required'; }
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die '--timeout-seconds must be a positive integer'

for tool in kubectl jq curl awk sed grep date seq sleep tail; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done

mkdir -p "$evidence_dir"
evidence_dir="$(cd -- "$evidence_dir" && pwd)"

# A unique account prevents one run's reservation state from changing another run's available
# notional. /proc/sys/kernel/random/uuid is available on the Linux environment required by kind and
# avoids making uuidgen an otherwise unnecessary host dependency.
[[ -r /proc/sys/kernel/random/uuid ]] || die '/proc/sys/kernel/random/uuid is required'
account_id="$(cat /proc/sys/kernel/random/uuid)"
run_id="rm1-$(date -u +%Y%m%d-%H%M%S)-$$"
helper_name="rm1-risk-matching-${run_id#rm1-}"
helper_name="${helper_name,,}"
helper_name="${helper_name//_/-}"
helper_name="${helper_name:0:63}"
port_forward_pid=""
port_forward_log="$evidence_dir/kafka-connect-port-forward.log"
helper_created=false

collect_diagnostics() {
  # Diagnostics are best-effort by design: they must never replace the original invariant failure.
  kubectl -n "$namespace" get pods -o wide \
    >"$evidence_dir/diagnostics-pods.txt" 2>&1 || true
  kubectl -n "$namespace" get deployments,statefulsets,jobs \
    >"$evidence_dir/diagnostics-workloads.txt" 2>&1 || true
  kubectl -n "$namespace" logs -l app.kubernetes.io/name=risk-service \
    --all-containers=true --prefix=true --tail=250 \
    >"$evidence_dir/diagnostics-risk.log" 2>&1 || true
  kubectl -n "$namespace" logs -l app.kubernetes.io/name=account-service \
    --all-containers=true --prefix=true --tail=250 \
    >"$evidence_dir/diagnostics-account.log" 2>&1 || true
  if [[ "$helper_created" == true ]]; then
    kubectl -n "$namespace" logs "pod/$helper_name" --all-containers=true \
      >"$evidence_dir/diagnostics-helper.log" 2>&1 || true
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
      jq -n --arg status FAIL --arg reason "RM-1 deployed verifier failed; inspect diagnostics" \
        '{status:$status, reason:$reason}' >"$evidence_dir/verdict.json" || true
    fi
  fi

  if [[ "$helper_created" == true && "$keep_helper" == false ]]; then
    kubectl -n "$namespace" delete pod "$helper_name" --ignore-not-found \
      --wait=false >/dev/null 2>&1 || true
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

# These are runtime prerequisites, not completion claims. Waiting here prevents an order rejection
# caused merely by racing service startup from being misreported as an RM-1 routing failure.
kubectl -n "$namespace" rollout status deployment/risk-service --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status deployment/account-service --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status deployment/kafka-connect --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status statefulset/postgres --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status statefulset/matching --timeout=600s >/dev/null

# The mounted ConfigMap is the runtime authority for both Risk and Matching. The Java verifier mounts
# the same object and runs the shared startup validator, so a stale/malformed/checksum-mismatched
# artifact fails before a command is submitted.
kubectl -n "$namespace" get configmap matching-daily-artifact -o json \
  >"$evidence_dir/market-reference-configmap.json"
artifact_day="$(
  kubectl -n "$namespace" get configmap matching-session-config \
    -o jsonpath='{.data.trading_day}'
)"
[[ "$artifact_day" == "$trading_day" ]] || die \
  "matching-session-config trading_day=$artifact_day, expected $trading_day"

# Verify the retained Risk connector, rather than merely checking that Kafka Connect Pods are Ready.
# A Ready worker with a FAILED connector/task cannot satisfy the Risk outbox -> Kafka behavior.
kubectl -n "$namespace" port-forward service/kafka-connect :8083 >"$port_forward_log" 2>&1 &
port_forward_pid="$!"
connect_port=""
for _ in $(seq 1 60); do
  if ! kill -0 "$port_forward_pid" >/dev/null 2>&1; then
    cat "$port_forward_log" >&2
    die 'Kafka Connect port-forward exited before becoming ready'
  fi
  connect_port="$(
    sed -nE 's/.*127\.0\.0\.1:([0-9]+) -> 8083.*/\1/p' "$port_forward_log" | tail -n 1
  )"
  [[ -n "$connect_port" ]] && break
  sleep 1
done
[[ -n "$connect_port" ]] || die 'could not resolve local Kafka Connect port-forward'

connector_status_url="http://127.0.0.1:${connect_port}/connectors/risk-service-outbox/status"
printf '%s\n' '{}' >"$evidence_dir/connector-status.json"
for _ in $(seq 1 60); do
  if curl -fsS "$connector_status_url" >"$evidence_dir/connector-status.json" 2>/dev/null \
      && jq -e \
        '.connector.state == "RUNNING"
         and (.tasks | length > 0)
         and ([.tasks[].state] | all(. == "RUNNING"))' \
        "$evidence_dir/connector-status.json" >/dev/null; then
    break
  fi
  sleep 1
done
jq -e \
  '.connector.state == "RUNNING"
   and (.tasks | length > 0)
   and ([.tasks[].state] | all(. == "RUNNING"))' \
  "$evidence_dir/connector-status.json" >/dev/null \
  || die 'risk-service-outbox connector/task is not RUNNING'

postgres_pod="$(
  kubectl -n "$namespace" get pods -l app.kubernetes.io/name=postgres \
    -o jsonpath='{.items[0].metadata.name}'
)"
[[ -n "$postgres_pod" ]] || die 'cannot resolve PostgreSQL Pod'

# Account Service looks up the ACCOUNT/* limit using its own Asia/Taipei clock. This can differ from
# an explicitly selected historical certification artifact, so seed the date Account will actually
# query rather than silently assuming the command trading day and wall-clock day are identical.
account_limit_day="$(TZ=Asia/Taipei date +%F)"
now_ms="$(( $(date +%s) * 1000 ))"
kubectl -n "$namespace" exec -i "$postgres_pod" -- \
  psql -U simplematch -d simplematch -v ON_ERROR_STOP=1 \
  >"$evidence_dir/account-fixture.log" 2>&1 <<SQL
INSERT INTO account_service.account_limits (
  account_id,
  scope_type,
  scope_key,
  trading_day,
  currency,
  limit_total_notional,
  reserved_notional,
  utilized_notional,
  available_notional,
  updated_at_unix_ms,
  version
) VALUES (
  '$account_id',
  'ACCOUNT',
  '*',
  DATE '$account_limit_day',
  'TWD',
  99999999999999999999.00000000,
  0,
  0,
  99999999999999999999.00000000,
  $now_ms,
  0
);
SQL

jq -n \
  --arg runId "$run_id" \
  --arg namespace "$namespace" \
  --arg tradingDay "$trading_day" \
  --arg accountLimitDay "$account_limit_day" \
  --arg accountId "$account_id" \
  --arg verifierImage "$image" \
  '{
    runId:$runId,
    namespace:$namespace,
    tradingDay:$tradingDay,
    accountLimitDay:$accountLimitDay,
    accountId:$accountId,
    verifierImage:$verifierImage
  }' >"$evidence_dir/run-metadata.json"

# The helper runs inside the namespace so Kafka's advertised in-cluster addresses are directly
# reachable. Reusing flyway-runner is intentional for the first retained verifier: it already
# contains the repository source and Java 25. The command copies the read-only image workspace into
# /tmp before Gradle so the Pod can keep a read-only root filesystem.
cat <<YAML | kubectl -n "$namespace" apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: $helper_name
  labels:
    app.kubernetes.io/name: risk-matching-e2e-verifier
    app.kubernetes.io/component: verification
    app.kubernetes.io/part-of: simplematch
spec:
  restartPolicy: Never
  securityContext:
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: verifier
      image: $image
      imagePullPolicy: IfNotPresent
      command: ["/bin/bash", "-lc"]
      args:
        - |
          set -euo pipefail
          work_dir=/tmp/simplematch-rm1-workspace
          cp -a /workspace/. "\$work_dir/"
          cd "\$work_dir"
          verifier_args="--artifact-path /etc/simplematch/market-reference/market_reference.json "
          verifier_args+="--checksum-path /etc/simplematch/market-reference/market_reference.sha256 "
          verifier_args+="--trading-day $trading_day --account-id $account_id "
          verifier_args+="--run-id $run_id --evidence-dir /evidence "
          verifier_args+="--timeout-seconds $timeout_seconds"
          exec ./gradlew --no-daemon \
            --gradle-user-home /tmp/gradle \
            --project-cache-dir /tmp/gradle-project \
            :tools:risk-matching-e2e-verifier:run \
            --args="\$verifier_args"
      securityContext:
        allowPrivilegeEscalation: false
        capabilities:
          drop: ["ALL"]
        readOnlyRootFilesystem: true
        runAsNonRoot: true
      resources:
        requests:
          cpu: 250m
          memory: 512Mi
        limits:
          cpu: "2"
          memory: 2Gi
      volumeMounts:
        - name: runtime-tmp
          mountPath: /tmp
        - name: evidence
          mountPath: /evidence
        - name: market-reference
          mountPath: /etc/simplematch/market-reference
          readOnly: true
  volumes:
    - name: runtime-tmp
      emptyDir: {}
    - name: evidence
      emptyDir: {}
    - name: market-reference
      configMap:
        name: matching-daily-artifact
        items:
          - key: market_reference.json
            path: market_reference.json
          - key: market_reference.sha256
            path: market_reference.sha256
YAML
helper_created=true

# Wait for either success or a terminal failure. kubectl wait for Ready is not appropriate for a
# short-lived batch Pod because a fast successful process can become Succeeded before Ready is
# observed.
deadline_epoch="$(( $(date +%s) + timeout_seconds + 300 ))"
while true; do
  phase="$(kubectl -n "$namespace" get pod "$helper_name" -o jsonpath='{.status.phase}')"
  case "$phase" in
    Succeeded) break ;;
    Failed)
      kubectl -n "$namespace" logs "$helper_name" --all-containers=true >&2 || true
      die 'verifier Pod failed'
      ;;
  esac
  (( $(date +%s) < deadline_epoch )) || die 'verifier Pod exceeded bounded deadline'
  sleep 2
done

kubectl -n "$namespace" logs "$helper_name" --all-containers=true \
  >"$evidence_dir/verifier.log" 2>&1 || true
kubectl -n "$namespace" cp "$helper_name:/evidence/." "$evidence_dir" >/dev/null

jq -e '.status == "PASS"' "$evidence_dir/verifier-verdict.json" >/dev/null \
  || die 'typed verifier did not report PASS'

command_id="$(jq -r '.commandId' "$evidence_dir/response.json")"
expected_partition="$(jq -r '.expectedPartition' "$evidence_dir/selected-instrument.json")"
expected_artifact_day="$(jq -r '.tradingDay' "$evidence_dir/selected-instrument.json")"
expected_artifact_sha="$(jq -r '.artifactContentSha256' "$evidence_dir/selected-instrument.json")"
expected_algorithm="$(jq -r '.routingAlgorithmVersion' "$evidence_dir/selected-instrument.json")"

# Query owner-owned Risk state only after the typed gRPC/Kafka probe succeeds. The query is evidence,
# not an alternate write path: the order itself entered exclusively through Risk gRPC.
admission_json="$(
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
)"
[[ -n "$admission_json" ]] || die "Risk admission row is missing for $command_id"
printf '%s\n' "$admission_json" | jq . >"$evidence_dir/risk-admission.json"

outbox_json="$(
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
      ORDER BY id DESC
      LIMIT 1;
    "
)"
[[ -n "$outbox_json" ]] || die "Risk matching.commands outbox row is missing for $command_id"
printf '%s\n' "$outbox_json" | jq . >"$evidence_dir/risk-outbox.json"

# Cross-layer assertions are intentionally duplicated here only for storage facts that the Java
# process cannot observe. Routing/business semantics remain owned by the typed verifier.
[[ "$(jq -r '.state' "$evidence_dir/risk-admission.json")" == ACCEPTED ]] \
  || die 'Risk durable Admission state is not ACCEPTED'
[[ "$(jq -r '.routingPartition' "$evidence_dir/risk-admission.json")" == "$expected_partition" ]] \
  || die 'Risk persisted routing partition differs from artifact assignment'
[[ "$(jq -r '.artifactTradingDay' "$evidence_dir/risk-admission.json")" == "$expected_artifact_day" ]] \
  || die 'Risk persisted artifact trading day differs from mounted artifact'
[[ "$(jq -r '.artifactContentSha256' "$evidence_dir/risk-admission.json")" == "$expected_artifact_sha" ]] \
  || die 'Risk persisted artifact checksum differs from mounted artifact'
[[ "$(jq -r '.routingAlgorithmVersion' "$evidence_dir/risk-admission.json")" == "$expected_algorithm" ]] \
  || die 'Risk persisted routing algorithm differs from mounted artifact'
[[ "$(jq -r '.topic' "$evidence_dir/risk-outbox.json")" == matching.commands ]] \
  || die 'Risk outbox topic is not matching.commands'
[[ "$(jq -r '.messageKey' "$evidence_dir/risk-outbox.json")" == "$command_id" ]] \
  || die 'Risk outbox Kafka key differs from commandId'
[[ "$(jq -r '.partition' "$evidence_dir/risk-outbox.json")" == "$expected_partition" ]] \
  || die 'Risk outbox partition differs from persisted artifact partition'
[[ "$(jq -r '.payloadType' "$evidence_dir/risk-outbox.json")" == \
    simplematch.matching.runtime.v1.MatchingCommand ]] \
  || die 'Risk outbox payload_type is not MatchingCommand'
[[ "$(jq -r '.payloadBase64' "$evidence_dir/risk-outbox.json")" == \
    "$(jq -r '.payloadBase64' "$evidence_dir/matching-command-record.json")" ]] \
  || die 'Kafka payload bytes differ from the durable Risk outbox payload'

jq -n \
  --arg status PASS \
  --arg commandId "$command_id" \
  --argjson partition "$expected_partition" \
  --arg artifactIdentity "${expected_artifact_day}:${expected_artifact_sha}" \
  '{
    status:$status,
    commandId:$commandId,
    partition:$partition,
    artifactIdentity:$artifactIdentity,
    provenPath:[
      "risk-service/SubmitNewOrder",
      "risk_service.admission_journal",
      "risk_service.outbox",
      "risk-service-outbox Debezium connector",
      "matching.commands"
    ]
  }' >"$evidence_dir/verdict.json"

printf 'RM-1 deployed Risk -> matching.commands verification passed: command_id=%s partition=%s\n' \
  "$command_id" "$expected_partition"

#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$script_dir/lib/local-certification-provenance.sh"

# RM-1 deployed verification intentionally reuses an already-running local production-like
# namespace. Kubernetes execution policy lives in the repository-owned Job manifest; this shell
# owns orchestration only: run facts, prerequisite checks, fixture setup, evidence collection, and
# cleanup.
namespace=""
trading_day=""
evidence_dir=""
account_id=""
side="BUY"
retained_evidence_dir=""
timeout_seconds="${SIMPLEMATCH_RM1_VERIFIER_TIMEOUT_SECONDS:-90}"
keep_helper=false

job_name="risk-matching-e2e-verifier"
run_config_name="risk-matching-e2e-run"
job_manifest="$repo_root/deploy/k8s/verification/risk-matching-e2e-verifier-job.yaml"
verifier_image="${SIMPLEMATCH_RM1_VERIFIER_IMAGE:-simplematch/risk-matching-e2e-verifier:local}"

usage() {
  cat <<'EOF'
Usage:
  scripts/run-risk-matching-command-e2e.sh \
    --namespace NAME \
    --trading-day YYYY-MM-DD \
    --evidence-dir PATH \
    [--account-id UUID] \
    [--side BUY|SELL] \
    [--verifier-image IMAGE] \
    --retained-evidence-dir PATH \
    [--timeout-seconds N] \
    [--keep-helper]

Proves the deployed RM-1 path:
  Risk gRPC -> Risk admission/outbox -> Debezium -> matching.commands.

The namespace must already have been created by the local production-like certification workflow.
The script does not create the kind cluster and does not delete application resources.

The verifier uses the repository-owned Job manifest:
  deploy/k8s/verification/risk-matching-e2e-verifier-job.yaml

--verifier-image selects the immutable image reference for this run. Pass the reference recorded by
the retained production-like image lock when the namespace uses registry transport; the default
local tag is kept for kind-load runs.

--side selects the order side. BUY provisions an account-wide cash limit; SELL provisions the
selected instrument's long position. Both fixtures enter through the public Risk -> Account path.

--retained-evidence-dir is required. It supplies the current source revision, image transport, and
immutable verifier digest used to validate the selected image before creating the Job.

--keep-helper preserves the verifier Job and its immutable run ConfigMap for inspection. A later run
in the same namespace will intentionally refuse to start until those retained helper resources are
removed.
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
    --account-id) account_id="${2:?--account-id requires a value}"; shift 2 ;;
    --side) side="${2:?--side requires a value}"; shift 2 ;;
    --verifier-image) verifier_image="${2:?--verifier-image requires a value}"; shift 2 ;;
    --retained-evidence-dir)
      retained_evidence_dir="${2:?--retained-evidence-dir requires a value}"
      shift 2
      ;;
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
case "${side^^}" in
  BUY|SIDE_BUY) side=BUY ;;
  SELL|SIDE_SELL) side=SELL ;;
  *) die '--side must be BUY or SELL' ;;
esac
if [[ -n "$account_id" &&
      ! "$account_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
  die '--account-id must be a canonical lowercase UUID'
fi
[[ -n "$retained_evidence_dir" ]] || die '--retained-evidence-dir is required'
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die '--timeout-seconds must be a positive integer'
(( timeout_seconds <= 300 )) || die \
  '--timeout-seconds must not exceed 300; the verifier Job has a separate 600-second active deadline'
[[ -n "$verifier_image" && "$verifier_image" != *[[:space:]]* ]] || die \
  '--verifier-image must be a non-empty image reference without whitespace'

for tool in kubectl jq curl awk sed grep date seq sleep tail base64 cat git docker; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
[[ -f "$job_manifest" ]] || die "verifier Job manifest does not exist: $job_manifest"

mkdir -p "$evidence_dir"
evidence_dir="$(cd -- "$evidence_dir" && pwd)"
shopt -s nullglob dotglob
existing_evidence=("$evidence_dir"/*)
shopt -u nullglob dotglob
((${#existing_evidence[@]} == 0)) || die \
  "evidence directory must be empty before verification: $evidence_dir"
rendered_job_manifest="$evidence_dir/risk-matching-e2e-verifier-job.yaml"
simplematch_render_verifier_helper_manifest \
  "$job_manifest" "$verifier_image" "$rendered_job_manifest" || die \
  'could not render the verifier Job with the selected image reference'

# A unique default prevents one run's reservation state from changing another run's available
# notional. A caller-supplied UUID is reserved for deterministic cross-certification fixtures that
# need the same public Account lifecycle identity. The override remains explicit and fail-closed.
if [[ -z "$account_id" ]]; then
  [[ -r /proc/sys/kernel/random/uuid ]] || die '/proc/sys/kernel/random/uuid is required'
  account_id="$(cat /proc/sys/kernel/random/uuid)"
fi
run_id="rm1-$(date -u +%Y%m%d-%H%M%S)-$$"
port_forward_pid=""
port_forward_log="$evidence_dir/kafka-connect-port-forward.log"
job_created=false
run_config_created=false
helper_pod=""

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

  if [[ "$job_created" == true ]]; then
    kubectl -n "$namespace" describe job "$job_name" \
      >"$evidence_dir/diagnostics-verifier-job.txt" 2>&1 || true
    kubectl -n "$namespace" logs "job/$job_name" --all-containers=true \
      >"$evidence_dir/diagnostics-helper.log" 2>&1 || true
  fi
  if [[ "$run_config_created" == true ]]; then
    kubectl -n "$namespace" get configmap "$run_config_name" -o json \
      >"$evidence_dir/diagnostics-run-config.json" 2>&1 || true
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

  if [[ "$keep_helper" == false ]]; then
    if [[ "$job_created" == true ]]; then
      kubectl -n "$namespace" delete job "$job_name" --ignore-not-found \
        --wait=false >/dev/null 2>&1 || true
    fi
    if [[ "$run_config_created" == true ]]; then
      kubectl -n "$namespace" delete configmap "$run_config_name" --ignore-not-found \
        --wait=false >/dev/null 2>&1 || true
    fi
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

retained_evidence_dir="$(cd -- "$retained_evidence_dir" && pwd)" || die \
  "cannot resolve retained evidence directory: $retained_evidence_dir"
retained_verifier_image="$(
  simplematch_certification_verifier_image \
    "$repo_root" "$namespace" "$retained_evidence_dir"
)" || die 'retained production-like source or image provenance is invalid'
[[ "$verifier_image" == "$retained_verifier_image" ]] || die \
  "selected verifier image does not match retained image: $retained_verifier_image"
simplematch_verify_kind_loaded_verifier_image_execution \
  "$repo_root" "$namespace" "$retained_evidence_dir" || die \
  'retained verifier image is not executable on every eligible kind node'

# Fixed helper identities deliberately serialize this verification within one disposable namespace.
# Silent replacement would destroy failure evidence and could make two concurrent orders appear to
# belong to one run, so stale or retained resources require an explicit operator decision.
if kubectl -n "$namespace" get job "$job_name" >/dev/null 2>&1 \
    || kubectl -n "$namespace" get configmap "$run_config_name" >/dev/null 2>&1; then
  die "verifier helper resources already exist; inspect or delete job/$job_name and configmap/$run_config_name before rerunning"
fi

# These are runtime prerequisites, not completion claims. Waiting here prevents an order rejection
# caused merely by racing service startup from being misreported as an RM-1 routing failure.
kubectl -n "$namespace" rollout status deployment/risk-service --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status deployment/account-service --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status deployment/kafka-connect --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status statefulset/postgres --timeout=300s >/dev/null
kubectl -n "$namespace" rollout status statefulset/matching --timeout=600s >/dev/null

# The mounted ConfigMap is the runtime authority for both Risk and Matching. The verifier Job mounts
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
artifact_instrument="$(
  jq -er '
    .data["market_reference.json"] | fromjson
    | [.marketSnapshot.instruments[]
       | select(.eligibility == "ELIGIBLE"
           and .referencePriceUnits != null
           and .lowerPriceLimitUnits != null
           and .upperPriceLimitUnits != null)]
    | sort_by([.venueMic, .symbol])
    | .[0]
  ' "$evidence_dir/market-reference-configmap.json"
)" || die 'mounted Market Reference has no eligible final-price instrument'
artifact_symbol="$(jq -er '.symbol' <<<"$artifact_instrument")" || die \
  'selected Market Reference instrument has no symbol'
artifact_rule_id="$(jq -er '.marketRuleId' <<<"$artifact_instrument")" || die \
  'selected Market Reference instrument has no market rule'
artifact_quantity="$(
  jq -er --arg rule "$artifact_rule_id" '
    .data["market_reference.json"] | fromjson
    | .marketRules.rules[] | select(.ruleId == $rule) | .boardLotShares
  ' "$evidence_dir/market-reference-configmap.json"
)" || die 'selected Market Reference instrument has no board lot quantity'

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

# The reservation operation carries the explicit certification trading day. Account authority
# looks up the ACCOUNT/* limit on that business day, so the fixture must use the same value rather
# than the host wall-clock date.
now_ms="$(( $(date +%s) * 1000 ))"
if [[ "$side" == BUY ]]; then
  account_fixture_sql="$(cat <<SQL
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
  DATE '$trading_day',
  'TWD',
  99999999999999999999.00000000,
  0,
  0,
  99999999999999999999.00000000,
  $now_ms,
  0
);
SQL
)"
else
  account_fixture_sql="$(cat <<SQL
INSERT INTO account_service.account_positions (
  account_id,
  symbol,
  long_qty,
  short_qty,
  reserved_long_qty,
  reserved_short_qty,
  updated_at_unix_ms,
  version
) VALUES (
  '$account_id',
  '$artifact_symbol',
  $artifact_quantity,
  0,
  0,
  0,
  $now_ms,
  0
);
SQL
)"
fi
kubectl -n "$namespace" exec -i "$postgres_pod" -- \
  psql -U simplematch -d simplematch -v ON_ERROR_STOP=1 \
  -c "$account_fixture_sql" >"$evidence_dir/account-fixture.log" 2>&1

jq -n \
  --arg runId "$run_id" \
  --arg namespace "$namespace" \
  --arg tradingDay "$trading_day" \
  --arg accountId "$account_id" \
  --arg side "$side" \
  --arg symbol "$artifact_symbol" \
  --argjson quantity "$artifact_quantity" \
  --arg verifierImage "$verifier_image" \
  '{runId:$runId, namespace:$namespace, tradingDay:$tradingDay,
    accountFixture:{side:$side,symbol:$symbol,quantity:$quantity},
    accountId:$accountId, verifierImage:$verifierImage}' \
  >"$evidence_dir/run-metadata.json"

# Run-specific values are data, not deployment policy. Keep them in one immutable ConfigMap rather
# than rendering them into YAML. The stable Job manifest consumes these exact keys with envFrom.
kubectl -n "$namespace" create configmap "$run_config_name" \
  --from-literal="SIMPLEMATCH_RM1_TRADING_DAY=$trading_day" \
  --from-literal="SIMPLEMATCH_RM1_ACCOUNT_ID=$account_id" \
  --from-literal="SIMPLEMATCH_RM1_SIDE=$side" \
  --from-literal="SIMPLEMATCH_RM1_RUN_ID=$run_id" \
  --from-literal="SIMPLEMATCH_RM1_TIMEOUT_SECONDS=$timeout_seconds" \
  --dry-run=client -o json \
  | jq '
      .immutable = true
      | .metadata.labels = {
          "app.kubernetes.io/name":"risk-matching-e2e-verifier",
          "app.kubernetes.io/component":"verification",
          "app.kubernetes.io/part-of":"simplematch"
        }
    ' \
  | kubectl -n "$namespace" create -f - >/dev/null
run_config_created=true
kubectl -n "$namespace" get configmap "$run_config_name" -o json \
  >"$evidence_dir/verifier-run-config.json"

# The Job manifest owns the Pod security/resources/volumes and the dedicated verifier image.
# No inline YAML, repository copy, or runtime Gradle invocation is permitted in this orchestration
# path.
kubectl -n "$namespace" create -f "$rendered_job_manifest" >/dev/null
job_created=true

# Resolve the controller-created Pod once. The fixed Job name and one-run-at-a-time preflight make
# this selector unambiguous.
for _ in $(seq 1 60); do
  helper_pod="$(
    kubectl -n "$namespace" get pods -l "job-name=$job_name" -o json 2>/dev/null \
      | jq -r '.items[0].metadata.name // empty'
  )"
  [[ -n "$helper_pod" ]] && break
  sleep 1
done
[[ -n "$helper_pod" ]] || die 'verifier Job did not create a Pod'

# The dedicated image keeps its wrapper alive after the Java verifier exits and exposes .ready only
# after all JSON evidence has been flushed. This explicit hand-off avoids trying to kubectl cp from
# an already terminated container.
handoff_deadline_epoch="$(( $(date +%s) + timeout_seconds + 240 ))"
while true; do
  if kubectl -n "$namespace" exec "$helper_pod" -- \
      test -f /tmp/evidence/.ready >/dev/null 2>&1; then
    break
  fi

  pod_json="$(kubectl -n "$namespace" get pod "$helper_pod" -o json)"
  phase="$(jq -r '.status.phase // ""' <<<"$pod_json")"
  if [[ "$phase" == Failed || "$phase" == Succeeded ]]; then
    kubectl -n "$namespace" logs "$helper_pod" --all-containers=true >&2 || true
    die "verifier Pod became terminal before evidence hand-off (phase=$phase)"
  fi

  (( $(date +%s) < handoff_deadline_epoch )) || die \
    'verifier did not expose evidence before the bounded hand-off deadline'
  sleep 2
done

kubectl -n "$namespace" logs "$helper_pod" --all-containers=true \
  >"$evidence_dir/verifier.log" 2>&1 || true
kubectl -n "$namespace" cp "$helper_pod:/tmp/evidence/." "$evidence_dir" >/dev/null

[[ -f "$evidence_dir/.exit-code" ]] || die 'verifier evidence is missing .exit-code'
verifier_exit_code="$(tr -d '[:space:]' <"$evidence_dir/.exit-code")"
[[ "$verifier_exit_code" =~ ^[0-9]+$ ]] || die 'verifier .exit-code is malformed'

# Acknowledge evidence collection so the image wrapper can return the original Java verifier status
# and let the Kubernetes Job reach its truthful terminal condition.
kubectl -n "$namespace" exec "$helper_pod" -- touch /tmp/evidence/.collected

job_deadline_epoch="$(( $(date +%s) + 60 ))"
job_terminal=""
while true; do
  job_json="$(kubectl -n "$namespace" get job "$job_name" -o json)"
  if jq -e \
      '[.status.conditions[]? | select(.type == "Complete" and .status == "True")] | length > 0' \
      <<<"$job_json" >/dev/null; then
    job_terminal="Complete"
    break
  fi
  if jq -e \
      '[.status.conditions[]? | select(.type == "Failed" and .status == "True")] | length > 0' \
      <<<"$job_json" >/dev/null; then
    job_terminal="Failed"
    break
  fi
  (( $(date +%s) < job_deadline_epoch )) || die \
    'verifier Job did not become terminal after evidence acknowledgement'
  sleep 1
done

if (( verifier_exit_code != 0 )); then
  die "typed verifier exited with status $verifier_exit_code"
fi
[[ "$job_terminal" == Complete ]] || die 'verifier Job reported Failed after a zero verifier exit code'
jq -e '.status == "PASS"' "$evidence_dir/verifier-verdict.json" >/dev/null \
  || die 'typed verifier did not report PASS'
jq -e '.terminalStatus == "ACCEPTED"' "$evidence_dir/admission-outcome.json" >/dev/null \
  || die 'typed verifier did not report a terminal accepted Admission outcome'

command_id="$(jq -r '.commandId' "$evidence_dir/request.json")"
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
  --arg admissionPath "$(jq -r '.path' "$evidence_dir/admission-outcome.json")" \
  --argjson partition "$expected_partition" \
  --arg artifactIdentity "${expected_artifact_day}:${expected_artifact_sha}" \
  '{
    status:$status,
    commandId:$commandId,
    admissionPath:$admissionPath,
    partition:$partition,
    artifactIdentity:$artifactIdentity,
    provenPath:[
      "risk-service/SubmitNewOrder",
      "risk-service/GetAdmissionOutcome when required",
      "risk_service.admission_journal",
      "risk_service.outbox",
      "risk-service-outbox Debezium connector",
      "matching.commands"
    ]
  }' >"$evidence_dir/verdict.json"

printf 'RM-1 deployed Risk -> matching.commands verification passed: command_id=%s partition=%s\n' \
  "$command_id" "$expected_partition"

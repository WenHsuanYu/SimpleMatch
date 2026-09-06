#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

# Focused Kafka Connect worker-loss diagnostic for Issue #156. It consumes an
# existing, owned namespace and never applies a deployment or deletes a cluster.
# The Connect worker-loss Module owns status interpretation; cdc-verifier.sh owns
# durable outbox selection and exact Kafka publication verification.

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"
# shellcheck source=scripts/lib/local-resilience.sh
source "$script_dir/lib/local-resilience.sh"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$script_dir/lib/local-certification-provenance.sh"
# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$script_dir/lib/local-certification-phase-graph.sh"
# shellcheck source=scripts/lib/local-certification-evidence.sh
source "$script_dir/lib/local-certification-evidence.sh"
# shellcheck source=scripts/lib/local-certification-images.sh
source "$script_dir/lib/local-certification-images.sh"
# shellcheck source=scripts/lib/local-certification-focused-diagnostic.sh
source "$script_dir/lib/local-certification-focused-diagnostic.sh"
# shellcheck source=scripts/lib/cdc-verifier.sh
source "$script_dir/lib/cdc-verifier.sh"
# shellcheck source=scripts/lib/connect-worker-loss.sh
source "$script_dir/lib/connect-worker-loss.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="${SIMPLEMATCH_KUBE_CONTEXT:-kind-$cluster_name}"
namespace="${SIMPLEMATCH_RESILIENCE_NAMESPACE:-}"
namespace_run_id="${SIMPLEMATCH_RESILIENCE_NAMESPACE_RUN_ID:-}"
evidence_dir="${SIMPLEMATCH_CONNECT_WORKER_LOSS_EVIDENCE_DIR:-}"
retained_evidence_dir="${SIMPLEMATCH_CONNECT_WORKER_LOSS_RETAINED_EVIDENCE_DIR:-${SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR:-out/certification/local-production-like}}"
deadline_seconds="${SIMPLEMATCH_CONNECT_WORKER_LOSS_DEADLINE_SECONDS:-$(connect_worker_loss_default_deadline_seconds)}"
verifier_contract_script="${SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT:-$script_dir/test-cdc-observer-fixture-contract.sh}"
dry_run=false

run_id="connect-worker-loss-$(date -u +%Y%m%dt%H%M%sz)-$$"
deadline_at=0
failure_reason=""
report_path=""
postgres_pod=""
kafka_pod=""
connect_url=""
connect_port_forward_pid=""
connect_port_forward_log_offset=0
fixture_aggregate_id=""
fixture_created=false
target_pod=""
target_uid=""
recovery_deadline_started_at_unix_ms=0
fault_requested_at_unix_ms=0
reassignment_observed_at_unix_ms=0

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/run-local-connect-worker-loss.sh \
    --namespace NAME --namespace-run-id ID [options]

Options:
  --namespace NAME       Existing disposable production-like namespace (required).
  --namespace-run-id ID  Exact value of the namespace run-id label (required).
  --context NAME         Kubernetes context (default: kind-simplematch-live).
  --cluster NAME         Canonical kind cluster name (default: simplematch-live).
  --retained-evidence-dir PATH
                         Source-aligned production-like evidence (default: SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR or out/certification/local-production-like).
  --evidence-dir PATH    Empty directory for this diagnostic report.
  --deadline-seconds N   Bounded fault/recovery deadline, at most 900 seconds (default: 600).
  --dry-run              Print the focused plan without changing state.

The diagnostic deletes exactly the Connect Pod that owns the Account connector
task, waits for a different task owner, then uses the shared CDC verifier to
prove one post-reassignment Account outbox transition reached account.lifecycle.
The retained runtime fingerprint must match; a verifier-only fingerprint change
is recorded and checked by the fast observer contract before mutation. It is
diagnostic evidence only; it is not a full-local certification PASS.
EOF_USAGE
}

die() {
  failure_reason="$*"
  printf 'Connect worker-loss diagnostic: %s\n' "$*" >&2
  exit 1
}

remaining_seconds() {
  local remaining=$((deadline_at - SECONDS))
  (( remaining > 0 )) && printf '%s\n' "$remaining" || printf '0\n'
}

run_bounded() {
  local remaining status
  remaining="$(remaining_seconds)"
  (( remaining > 0 )) || {
    failure_reason="diagnostic exceeded the ${deadline_seconds}s deadline"
    return 124
  }
  if timeout --foreground "${remaining}s" "$@"; then
    return 0
  else
    status="$?"
  fi
  if (( status == 124 )); then
    failure_reason="bounded command timed out: ${1:-command}"
  fi
  return "$status"
}

kube() {
  run_bounded kubectl --context "$context" "$@"
}

kns() {
  kube -n "$namespace" "$@"
}

short_cleanup() {
  timeout --foreground 30s kubectl --context "$context" -n "$namespace" "$@"
}

write_failure_report() {
  local reason="${failure_reason:-diagnostic did not complete}"
  jq -n \
    --argjson schema_version "$CONNECT_WORKER_LOSS_REPORT_SCHEMA_VERSION" \
    --arg status FAILED --arg cluster "$cluster_name" --arg context "$context" \
    --arg namespace "$namespace" --arg namespace_run_id "$namespace_run_id" \
    --arg run_id "$run_id" --argjson deadline_seconds "$deadline_seconds" \
    --arg reason "$reason" \
    '{schema_version:$schema_version,profile:"connect-worker-loss",status:$status,
      cluster:$cluster,context:$context,namespace:$namespace,
      namespace_run_id:$namespace_run_id,run_id:$run_id,fault_mode:"pod-delete",
      deadline_seconds:$deadline_seconds,recovery_deadline_started_at_unix_ms:0,
      prerequisites:{},task_reassignment:{},
      publication:{},failure_reason:$reason,
      claim_boundary:["focused local Kafka Connect worker-loss diagnostic"]}' \
    >"$report_path"
}

cleanup_fixture() {
  local sql remaining
  [[ "$fixture_created" == true ]] || return 0
  [[ -n "$postgres_pod" && -n "$fixture_aggregate_id" ]] || return 1
  sql="BEGIN;
DELETE FROM account_service.outbox
 WHERE aggregate_type = 'account_reservation'
   AND aggregate_id = '$fixture_aggregate_id';
SELECT count(*) FROM account_service.outbox
 WHERE aggregate_type = 'account_reservation'
   AND aggregate_id = '$fixture_aggregate_id';
COMMIT;"
  remaining="$(short_cleanup exec "$postgres_pod" -c postgres -- psql \
    --username=simplematch --dbname=simplematch --no-psqlrc --tuples-only --no-align \
    --set=ON_ERROR_STOP=1 --command "$sql" 2>/dev/null | tail -n 2 | head -n 1 | tr -d '[:space:]')" || return 1
  [[ "$remaining" == 0 ]] || return 1
  fixture_created=false
}

stop_connect_port_forward() {
  local pid="$1" deadline="${2:-0}" iteration

  kill "$pid" >/dev/null 2>&1 || true
  for ((iteration = 0; iteration < 50; iteration++)); do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      wait "$pid" >/dev/null 2>&1 || true
      connect_port_forward_pid=""
      return 0
    fi
    if [[ "$deadline" =~ ^[1-9][0-9]*$ ]] && (( SECONDS >= deadline )); then
      break
    fi
    sleep 0.1
  done
  kill -KILL "$pid" >/dev/null 2>&1 || true
  wait "$pid" >/dev/null 2>&1 || true
  connect_port_forward_pid=""
  if [[ "$deadline" =~ ^[1-9][0-9]*$ ]] && (( SECONDS >= deadline )); then
    return 1
  fi
}

validate_relative_path() {
  local path="$1" label="$2"

  case "$path" in
    ''|/*|..|../*|*/../*|*/..)
      die "$label must be a repository-relative path: $path"
      ;;
  esac
}

cleanup() {
  local status="$?" cleanup_status=0
  set +e
  if [[ -n "$connect_port_forward_pid" ]]; then
    stop_connect_port_forward "$connect_port_forward_pid"
  fi
  if [[ "$fixture_created" == true ]]; then
    cleanup_fixture || cleanup_status=1
  fi
  if (( cleanup_status != 0 )); then
    failure_reason="${failure_reason:-could not remove the run-owned Account outbox fixture}"
    status=1
  fi
  if (( status != 0 )); then
    write_failure_report
  fi
  trap - EXIT
  exit "$status"
}

write_provenance_evidence() {
  local current_commit retained_verifier_image_identity

  current_commit="$(git -C "$repo_root" rev-parse HEAD)" ||
    die 'could not record the current source revision'
  retained_verifier_image_identity="$(
    simplematch_certification_verifier_image_identity "$retained_evidence_dir"
  )" || die 'retained verifier image identity is missing or malformed'
  jq -n \
    --arg status PASS --arg evidence_dir "$retained_evidence_dir" \
    --arg namespace "$namespace" --arg namespace_run_id "$namespace_run_id" \
    --arg commit "$current_commit" \
    --arg runtime "$SIMPLEMATCH_FOCUSED_CURRENT_CDC_RUNTIME_SIGNATURE" \
    --arg retained_runtime "$SIMPLEMATCH_FOCUSED_RETAINED_CDC_RUNTIME_SIGNATURE" \
    --arg verifier "$SIMPLEMATCH_FOCUSED_CURRENT_CDC_VERIFIER_SIGNATURE" \
    --arg retained_verifier "$SIMPLEMATCH_FOCUSED_RETAINED_CDC_VERIFIER_SIGNATURE" \
    --arg image "$retained_verifier_image_identity" \
    --arg observer_path "$(simplematch_focused_verifier_observer_path)" \
    --arg observer_sha256 "$(simplematch_focused_verifier_observer_sha256)" \
    --arg observer_evidence_file \
      "$(simplematch_focused_verifier_observer_evidence_file)" \
    --arg contract_path "$(simplematch_focused_verifier_contract_path)" \
    --arg contract_sha256 "$(simplematch_focused_verifier_contract_sha256)" \
    --argjson verifier_changed \
      "$([[ "$(simplematch_focused_verifier_changed)" == true ]] &&
        printf true || printf false)" \
    '{status:$status,retained_evidence_dir:$evidence_dir,namespace:$namespace,
      namespace_run_id:$namespace_run_id,current_commit:$commit,
      cdc_runtime_signature:$runtime,retained_cdc_runtime_signature:$retained_runtime,
      cdc_verifier_signature:$verifier,retained_cdc_verifier_signature:$retained_verifier,
      verifier_signature_changed:$verifier_changed,runtime_reused:true,
      verifier_image_identity:$image,verifier_observer_path:$observer_path,
      verifier_observer_sha256:$observer_sha256,
      verifier_observer_evidence_file:$observer_evidence_file,
      verifier_contract_path:$contract_path,
      verifier_contract_sha256:$contract_sha256,
      verifier_contract_evidence_file:"verifier-contract.sh"}' \
    >"$evidence_dir/provenance.json" || die 'could not write provenance evidence'
}

validate_cluster() {
  local contexts nodes_json worker_count ready_workers
  contexts="$(run_bounded kubectl config get-contexts -o name)" || die 'could not inspect Kubernetes contexts'
  grep -Fxq "$context" <<<"$contexts" || die "Kubernetes context is not configured: $context"
  run_bounded kind get clusters | grep -Fxq "$cluster_name" ||
    die "canonical kind cluster is not available: $cluster_name"
  nodes_json="$(kube get nodes -o json)" || die 'could not read canonical kind nodes'
  printf '%s\n' "$nodes_json" >"$evidence_dir/nodes.json"
  worker_count="$(jq '[.items[] | select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience")] | length' <<<"$nodes_json")"
  ready_workers="$(jq '[.items[] | select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience") | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | length' <<<"$nodes_json")"
  (( worker_count >= 2 && ready_workers >= 2 )) ||
    die 'at least two Ready local-resilience workers are required for Connect reassignment'
  simplematch_kind_validate_control_plane_stability "$context" 5 60 \
    "$evidence_dir/control-plane" 60 ||
    die 'canonical Kubernetes control plane is not stable before Connect fault injection'
}

capture_prerequisite() {
  local name="$1" kind="$2"
  local output="$evidence_dir/prerequisites/$name.json"
  kns get "$kind" "$name" -o json >"$output" || die "required prerequisite is missing: $kind/$name"
  jq -e '
    if .kind == "Job" then
      any(.status.conditions[]?; .type == "Complete" and .status == "True")
    elif .kind == "StatefulSet" then
      (.status.readyReplicas // 0) >= (.spec.replicas // 1)
    else true end
  ' "$output" >/dev/null || die "required prerequisite is not complete: $kind/$name"
}

validate_prerequisites() {
  local deployment_json pdb_json pods_file
  mkdir -p "$evidence_dir/prerequisites"
  capture_prerequisite kafka-topic-provisioning job
  for service in account-service risk-service persistence market-data-projection query-service quickfix-gateway; do
    capture_prerequisite "$service-flyway" job
  done
  capture_prerequisite postgres statefulset
  kns rollout status statefulset/postgres --timeout="$(remaining_seconds)s" >/dev/null ||
    die 'PostgreSQL was not Ready before Connect observation'

  kns get configmap simplematch-kafka-connect-config -o json \
    >"$evidence_dir/connect-config.json" ||
    die 'Kafka Connect profile ConfigMap is missing'
  deployment_json="$(kns get deployment kafka-connect -o json)" || die 'Kafka Connect Deployment is missing'
  pdb_json="$(kns get pdb kafka-connect -o json)" || die 'Kafka Connect PDB is missing'
  printf '%s\n' "$deployment_json" >"$evidence_dir/connect-deployment.json"
  printf '%s\n' "$pdb_json" >"$evidence_dir/connect-pdb.json"
  jq -e '
    .spec.replicas == 2 and
    (.spec.template.spec.volumes // [] | all(.persistentVolumeClaim == null)) and
    any(.spec.template.spec.containers[]?;
      .name == "kafka-connect" and .image == "quay.io/debezium/connect:3.6.0.Final")
  ' <<<"$deployment_json" >/dev/null ||
    die 'Kafka Connect must use the pinned Debezium 3.6 image, two replicas, and no PVC-backed volume'
  jq -e '
    .spec.minAvailable == 1 and
    .spec.selector.matchLabels["app.kubernetes.io/name"] == "kafka-connect" and
    .spec.selector.matchLabels["app.kubernetes.io/component"] == "connector"
  ' <<<"$pdb_json" >/dev/null || die 'Kafka Connect PDB must protect one worker'

  pods_file="$evidence_dir/connect-pods-before.json"
  kns get pods -l app.kubernetes.io/name=kafka-connect,app.kubernetes.io/component=connector \
    -o json >"$pods_file" || die 'could not capture Kafka Connect Pods'
  connect_worker_loss_pods_are_valid "$pods_file" || return 1
  jq -e '
    [.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
      | .spec.nodeName] | unique | length == 2
  ' "$pods_file" >/dev/null || die 'Connect workers must be spread across two nodes'

  for connector in account-service-outbox risk-service-outbox; do
    local config_file="$evidence_dir/${connector}-configmap.json"
    kns get configmap "$connector-connector" -o json >"$config_file" ||
      die "service-owned connector ConfigMap is missing: $connector"
    local table
    case "$connector" in
      account-service-outbox) table='account_service.outbox' ;;
      risk-service-outbox) table='risk_service.outbox' ;;
      *) die "unsupported service-owned connector: $connector" ;;
    esac
    jq -e --arg connector "$connector" --arg table "$table" '
      (.data["connector.json"] | fromjson) as $document |
      ($document.name == $connector) and
      ($document.config["table.include.list"] == $table) and
      ($document.config["transforms.outbox.table.fields.additional.placement"]
        | contains("headers_json:header:headers_json")) and
      ($document.config["transforms.outbox.table.fields.additional.placement"]
        | contains("payload_type:header:eventType"))
    ' "$config_file" >/dev/null || die "${connector} does not preserve its owner outbox boundary"
  done

  kafka_pod="$(kns get pods -l app.kubernetes.io/name=kafka,app.kubernetes.io/component=broker \
    -o json | jq -er '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))][0].metadata.name')" ||
    die 'no Ready Kafka Pod is available for internal topic checks'
  for topic in simplematch-connect-configs simplematch-connect-offsets simplematch-connect-status; do
    local description configuration
    description="$(kafka_exec /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
      --describe --topic "$topic")" || die "could not inspect Kafka topic $topic"
    configuration="$(kafka_exec /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka:9092 \
      --entity-type topics --entity-name "$topic" --describe --all)" ||
      die "could not inspect Kafka topic configuration $topic"
    grep -Fq 'ReplicationFactor: 3' <<<"$description" || die "Kafka topic $topic is not RF3"
    grep -Eq 'min.insync.replicas[=:]2' <<<"$configuration" || die "Kafka topic $topic does not require ISR2"
    {
      printf '%s\n' "### $topic"
      printf '%s\n' "$description" "$configuration"
    } >"$evidence_dir/prerequisites/$topic.txt"
  done
}

start_connect_port_forward() {
  local log_path="$evidence_dir/connect-port-forward.log" port
  if [[ -n "$connect_port_forward_pid" ]]; then
    stop_connect_port_forward "$connect_port_forward_pid" "$deadline_at" ||
      die 'Kafka Connect port-forward teardown exceeded the diagnostic deadline'
  fi
  if [[ -e "$log_path" || -L "$log_path" ]]; then
    [[ -f "$log_path" && ! -L "$log_path" ]] ||
      die 'Kafka Connect port-forward log is not a regular file'
    connect_port_forward_log_offset="$(wc -c <"$log_path")" ||
      die 'could not read the Kafka Connect port-forward log size'
  else
    connect_port_forward_log_offset=0
  fi
  printf '%s\n' "Starting Kafka Connect service port-forward" >>"$log_path"
  kubectl --context "$context" -n "$namespace" \
    port-forward service/kafka-connect :8083 >>"$log_path" 2>&1 &
  connect_port_forward_pid="$!"
  for _ in $(seq 1 30); do
    if ! kill -0 "$connect_port_forward_pid" >/dev/null 2>&1; then
      cat "$log_path" >&2
      die 'Kafka Connect port-forward exited before becoming ready'
    fi
    if port="$(simplematch_port_forward_port "$log_path" \
      "$connect_port_forward_log_offset" 8083)" && [[ -n "$port" ]]; then
      connect_url="http://127.0.0.1:${port}"
      return 0
    fi
    run_bounded sleep 1 || die 'Kafka Connect port-forward did not become ready before timeout'
  done
  die 'could not resolve Kafka Connect port-forward port'
}

restart_connect_port_forward() {
  local reason="${1:-Kafka Connect REST tunnel became unavailable}"

  printf '%s\n' "Restarting Kafka Connect service port-forward: $reason" \
    >>"$evidence_dir/connect-port-forward.log"
  start_connect_port_forward
}

connect_status() {
  local connector="$1"
  run_bounded curl -fsS --connect-timeout 2 --max-time 5 \
    "$connect_url/connectors/$connector/status"
}

wait_connector_running() {
  local connector="$1" output_file="$2" status_file="${2}.attempt"
  while true; do
    if connect_status "$connector" >"$status_file" 2>/dev/null; then
      if jq -e '
        .connector.state == "RUNNING" and
        (.tasks | type == "array" and length > 0) and
        all(.tasks[]; .state == "RUNNING")
      ' "$status_file" >/dev/null 2>&1; then
        mv -- "$status_file" "$output_file"
        return 0
      fi
    else
      remaining_seconds | grep -Eq '^[1-9][0-9]*$' ||
        die "${connector} did not become RUNNING before timeout"
      restart_connect_port_forward \
        "${connector} REST status tunnel became unavailable"
      run_bounded sleep 1 || die "${connector} did not become RUNNING before timeout"
      continue
    fi
    remaining_seconds | grep -Eq '^[1-9][0-9]*$' || die "${connector} did not become RUNNING before timeout"
    run_bounded sleep 1 || die "${connector} did not become RUNNING before timeout"
  done
}

capture_target_slot() {
  local target_file="$1" node slot temporary
  node="$(jq -er '.node' "$target_file")" || return 1
  slot="$(kube get node "$node" -o jsonpath='{.metadata.labels.simplematch\.io/worker-slot}')" || return 1
  [[ "$slot" =~ ^[0-9]+$ ]] || die "Connect node has no worker-slot label: $node"
  temporary="${target_file}.tmp"
  jq --arg slot "$slot" '.worker_slot = $slot' "$target_file" >"$temporary" && mv -- "$temporary" "$target_file"
}

recheck_target_before_delete() {
  local status_file="$evidence_dir/connect-status-before-delete.json"
  local pods_file="$evidence_dir/connect-pods-before-delete.json"
  local target_file="$evidence_dir/task-owner-before-delete.json"
  local pod_file="$evidence_dir/pod-pre-delete.json"
  local status_json pod_json current_uid current_name current_ip current_node
  local expected_worker expected_task

  status_json="$(connect_status account-service-outbox)" ||
    die 'could not re-read Account connector status before fault injection'
  printf '%s\n' "$status_json" >"$status_file"
  kns get pods -l app.kubernetes.io/name=kafka-connect,app.kubernetes.io/component=connector \
    -o json >"$pods_file" || die 'could not re-read Connect Pods before fault injection'
  connect_worker_loss_target_identity "$status_file" "$pods_file" "$target_file" ||
    die 'Account connector task owner changed before fault injection'
  capture_target_slot "$target_file"

  expected_worker="$(jq -er '.worker_id' "$evidence_dir/task-owner-before.json")" || return 1
  expected_task="$(jq -er '.task_id' "$evidence_dir/task-owner-before.json")" || return 1
  jq -e --arg worker "$expected_worker" --argjson task "$expected_task" '
    .worker_id == $worker and .task_id == $task
  ' "$target_file" >/dev/null || die 'Account connector task assignment changed before fault injection'
  current_name="$(jq -er '.pod' "$target_file")" || return 1
  current_uid="$(jq -er '.pod_uid' "$target_file")" || return 1
  [[ "$current_name" == "$target_pod" && "$current_uid" == "$target_uid" ]] ||
    die 'task owner Pod identity changed between baseline and the delete recheck'

  pod_json="$(kns get pod "$target_pod" -o json)" ||
    die "task-owning Connect Pod disappeared before fault injection: $target_pod"
  printf '%s\n' "$pod_json" >"$pod_file"
  current_ip="$(jq -er '.pod_ip' "$target_file")" || return 1
  current_node="$(jq -er '.node' "$target_file")" || return 1
  jq -e --arg pod "$target_pod" --arg uid "$target_uid" --arg ip "$current_ip" \
      --arg node "$current_node" '
    .metadata.name == $pod and .metadata.uid == $uid and
    (.metadata.deletionTimestamp // null) == null and
    .spec.nodeName == $node and .status.podIP == $ip and
    .metadata.labels["app.kubernetes.io/name"] == "kafka-connect" and
    .metadata.labels["app.kubernetes.io/component"] == "connector" and
    any(.status.conditions[]?; .type == "Ready" and .status == "True")
  ' "$pod_file" >/dev/null || die 'task-owning Connect Pod is not Ready with the expected UID'
}

wait_for_deleted_target() {
  local error_file="$evidence_dir/target-delete-observation.log"
  local observation_file="$evidence_dir/target-delete-observation.json"
  local pod_json observed_uid outcome observed_at

  : >"$error_file"
  while true; do
    if pod_json="$(kns get pod "$target_pod" -o json 2>"$error_file")"; then
      observed_uid="$(jq -r '.metadata.uid // empty' <<<"$pod_json" 2>/dev/null || true)"
      if [[ -n "$observed_uid" && "$observed_uid" != "$target_uid" ]]; then
        jq -e --arg run_id "$run_id" \
          '(.metadata.labels["simplematch.io/worker-loss-run"] // "") != $run_id' \
          <<<"$pod_json" >/dev/null || die 'a replacement Pod inherited the worker-loss marker'
        outcome=replacement-pod
        observed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        jq -n --arg pod "$target_pod" --arg uid "$target_uid" \
          --arg outcome "$outcome" --arg replacement_uid "$observed_uid" \
          --arg observed_at "$observed_at" \
          '{schema_version:1,target_pod:$pod,target_pod_uid:$uid,
            outcome:$outcome,replacement_pod_uid:$replacement_uid,
            target_uid_absent:true,observed_at_utc:$observed_at}' \
          >"$observation_file" || die 'could not record the Pod deletion observation'
        return 0
      fi
    elif grep -Eqi 'notfound|not found' "$error_file"; then
      outcome=not-found
      observed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      jq -n --arg pod "$target_pod" --arg uid "$target_uid" \
        --arg outcome "$outcome" --arg observed_at "$observed_at" \
        '{schema_version:1,target_pod:$pod,target_pod_uid:$uid,
          outcome:$outcome,replacement_pod_uid:null,
          target_uid_absent:true,observed_at_utc:$observed_at}' \
        >"$observation_file" || die 'could not record the Pod deletion observation'
      return 0
    fi
    remaining_seconds | grep -Eq '^[1-9][0-9]*$' ||
      die 'deleted Connect Pod UID did not disappear before the diagnostic deadline'
    run_bounded sleep 1 || die 'deleted Connect Pod UID did not disappear before timeout'
  done
}

delete_target_pod() {
  local selector="simplematch.io/worker-loss-run=$run_id"
  local patch marked_pods patch_uid patch_name delete_output precondition

  patch="$(jq -cn --arg uid "$target_uid" --arg run_id "$run_id" '
    [{op:"test",path:"/metadata/uid",value:$uid},
     {op:"add",path:"/metadata/labels/simplematch.io~1worker-loss-run",value:$run_id}]
  ')"
  kns patch pod "$target_pod" --type=json -p "$patch" -o json \
    >"$evidence_dir/pod-patch.json" || die 'UID-guarded marker patch failed before Pod deletion'
  jq -e --arg pod "$target_pod" --arg uid "$target_uid" --arg run_id "$run_id" '
    .metadata.name == $pod and .metadata.uid == $uid and
    .metadata.labels["simplematch.io/worker-loss-run"] == $run_id
  ' "$evidence_dir/pod-patch.json" >/dev/null ||
    die 'UID-guarded marker patch did not return the expected task-owning Pod'
  marked_pods="$(kns get pods -l "$selector" -o json)" ||
    die 'could not verify the uniquely marked task-owning Pod'
  patch_uid="$(jq -er '[.items[]] | if length == 1 then .[0].metadata.uid else empty end' \
    <<<"$marked_pods")" || die 'worker-loss marker did not identify exactly one Pod'
  patch_name="$(jq -er '[.items[]] | if length == 1 then .[0].metadata.name else empty end' \
    <<<"$marked_pods")" || return 1
  [[ "$patch_uid" == "$target_uid" && "$patch_name" == "$target_pod" ]] ||
    die 'worker-loss marker resolved to a different Pod identity'
  precondition="$(kns get pod "$target_pod" -o json)" ||
    die 'task-owning Connect Pod disappeared before the UID delete precondition'
  printf '%s\n' "$precondition" >"$evidence_dir/pod-delete-precondition.json"
  jq -e --arg pod "$target_pod" --arg uid "$target_uid" --arg selector_value "$run_id" '
    .metadata.name == $pod and .metadata.uid == $uid and
    .metadata.labels["simplematch.io/worker-loss-run"] == $selector_value and
    (.metadata.deletionTimestamp // null) == null
  ' <<<"$precondition" >/dev/null ||
    die 'UID delete precondition no longer identifies the task-owning Pod'
  fault_requested_at_unix_ms="$(date +%s%3N)"
  delete_output="$(kns delete pods -l "$selector" --wait=false)" ||
    die "could not delete the marked task-owning Connect Pod: $target_pod"
  grep -Fq "$target_pod" <<<"$delete_output" ||
    die 'Pod deletion selector did not report the intended task-owning Pod'
  wait_for_deleted_target
  jq -n --arg pod "$target_pod" --arg pod_uid "$target_uid" \
      --arg selector "$selector" \
      --arg output "$delete_output" \
      --argjson recovery_started_at_unix_ms "$recovery_deadline_started_at_unix_ms" \
      --argjson requested_at_unix_ms "$fault_requested_at_unix_ms" \
      --arg requested_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      '{fault:"pod-delete",target_pod:$pod,target_pod_uid:$pod_uid,
        delete_selector:$selector,delete_output:$output,
        delete_requested:true,uid_precondition_test:true,pre_delete_recheck:true,
        delete_output_contains_target:true,target_uid_absent:true,
        recovery_deadline_started_at_unix_ms:$recovery_started_at_unix_ms,
        requested_at_unix_ms:$requested_at_unix_ms,
        requested_at:$requested_at}' >"$evidence_dir/worker-loss.json"
}

wait_for_reassignment() {
  local before_status="$1" before_target="$2" deleted_pod="$3" deleted_uid="$4"
  local candidate_status="$evidence_dir/connect-status-after-candidate.json"
  local candidate_pods="$evidence_dir/connect-pods-after-candidate.json"
  local candidate_target="$evidence_dir/task-owner-after-candidate.json"
  local final_status="$evidence_dir/connect-status-after-reassignment.json"
  local final_pods="$evidence_dir/connect-pods-after-reassignment.json"
  local final_target="$evidence_dir/task-owner-after-reassignment.json"

  while true; do
    kns get pods -l app.kubernetes.io/name=kafka-connect,app.kubernetes.io/component=connector \
      -o json >"$candidate_pods" || true
    if ! connect_status account-service-outbox >"$candidate_status" 2>/dev/null; then
      remaining_seconds | grep -Eq '^[1-9][0-9]*$' ||
        die 'Connect task was not reassigned before the diagnostic deadline'
      restart_connect_port_forward \
        'the service tunnel no longer reached a live Connect Pod after worker loss'
      run_bounded sleep 1 ||
        die 'Connect task was not reassigned before the diagnostic deadline'
      continue
    fi
    if jq -e --arg pod "$deleted_pod" --arg uid "$deleted_uid" \
        'all(.items[]?; .metadata.name != $pod and .metadata.uid != $uid)' \
        "$candidate_pods" >/dev/null 2>&1 &&
      connect_worker_loss_target_identity "$candidate_status" "$candidate_pods" "$candidate_target" \
      >/dev/null 2>&1; then
      capture_target_slot "$candidate_target"
      if connect_worker_loss_assert_reassignment \
          "$before_status" "$candidate_status" "$before_target" "$candidate_target"; then
        cp -- "$candidate_status" "$final_status"
        cp -- "$candidate_pods" "$final_pods"
        cp -- "$candidate_target" "$final_target"
        connect_worker_loss_pods_are_valid "$final_pods" || return 1
        return 0
      fi
    fi
    remaining_seconds | grep -Eq '^[1-9][0-9]*$' ||
      die 'Connect task was not reassigned before the diagnostic deadline'
    run_bounded sleep 2 || die 'Connect task was not reassigned before the diagnostic deadline'
  done
}

postgres_exec() {
  local sql="$1"
  kns exec "$postgres_pod" -c postgres -- psql --username=simplematch --dbname=simplematch \
    --no-psqlrc --tuples-only --no-align --field-separator $'\t' \
    --set=ON_ERROR_STOP=1 --command "$sql"
}

kafka_exec() {
  kns exec "$kafka_pod" -c kafka -- "$@"
}

insert_account_outbox_row() {
  local event_id="$1" created_at="$2" payload_hex="$3" headers_json="$4" purpose="$5"
  local apostrophe="'"
  local escaped_headers="${headers_json//$apostrophe/$apostrophe$apostrophe}"
  postgres_exec "BEGIN;
INSERT INTO account_service.outbox (
  event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
  aggregate_type, aggregate_id, created_at_unix_ms, created_at
) VALUES (
  '$event_id'::uuid, 'account.lifecycle', '$account_id', NULL, decode('$payload_hex', 'hex'),
  '$account_payload_type', '$escaped_headers', 'account_reservation', '$fixture_aggregate_id',
  $created_at, to_timestamp($created_at / 1000.0) AT TIME ZONE 'UTC'
);
COMMIT;" >/dev/null || die "$purpose"
}

seed_account_history() {
  local history_event="$1" created_at="$2" payload_hex="$3" headers_json="$4"
  insert_account_outbox_row "$history_event" "$created_at" "$payload_hex" "$headers_json" \
    'could not seed the historical Account outbox row'
}

insert_account_transition() {
  local transition_event="$1" created_at="$2" payload_hex="$3" headers_json="$4"
  insert_account_outbox_row "$transition_event" "$created_at" "$payload_hex" "$headers_json" \
    'could not commit the post-reassignment Account transition fixture'
}

run_publication_check() {
  local reassignment_observed_at_unix_ms="$1"
  local now_ms history_event transition_event history_payload transition_payload
  local transition_created_at_unix_ms
  local history_headers transition_headers baseline_file kafka_baseline probe_file
  now_ms="$(( $(date +%s) * 1000 ))"
  history_event="$(cat /proc/sys/kernel/random/uuid)"
  transition_event="$(cat /proc/sys/kernel/random/uuid)"
  fixture_aggregate_id="${run_id}-reservation"
  account_id="$(cat /proc/sys/kernel/random/uuid)"
  account_payload_type='simplematch.account.v2.AccountLifecycleEvent'
  history_payload="$(printf 'account-history-%s' "$run_id" | od -An -tx1 | tr -d ' \n')"
  transition_payload="$(printf 'account-transition-%s' "$run_id" | od -An -tx1 | tr -d ' \n')"
  history_headers="$(jq -cn --arg event_id "$history_event" --arg payload_type "$account_payload_type" \
    '{event_id:$event_id,content_type:"application/x-protobuf",payload_type:$payload_type}')"
  transition_headers="$(jq -cn --arg event_id "$transition_event" --arg payload_type "$account_payload_type" \
    '{event_id:$event_id,content_type:"application/x-protobuf",payload_type:$payload_type}')"
  seed_account_history "$history_event" "$now_ms" "$history_payload" "$history_headers"
  fixture_created=true

  baseline_file="$evidence_dir/account-outbox-baseline.json"
  kafka_baseline="$evidence_dir/account-kafka-baseline.tsv"
  probe_file="$evidence_dir/account-outbox-probe.json"
  cdc_capture_outbox_baseline account_service account_reservation "$fixture_aggregate_id" "$baseline_file" ||
    die 'shared CDC verifier could not capture the Account lifecycle baseline'
  cdc_capture_topic_end_offsets account.lifecycle "$kafka_baseline" ||
    die 'shared CDC verifier could not capture the Account Kafka baseline'
  transition_created_at_unix_ms="$((now_ms + 1))"
  insert_account_transition "$transition_event" "$transition_created_at_unix_ms" \
    "$transition_payload" "$transition_headers"
  cdc_read_outbox_probe account_service account_reservation "$fixture_aggregate_id" \
    "$probe_file" "$baseline_file" || die 'shared CDC verifier could not locate the post-transition Account event'
  cdc_assert_probe_publication "$probe_file" "$kafka_baseline" \
    "$evidence_dir/account-publication.json" ||
    die 'post-reassignment Account transition was not published exactly to account.lifecycle'
  jq -n --arg aggregate_id "$fixture_aggregate_id" --arg event_id "$transition_event" \
    --arg payload_type "$account_payload_type" \
    --argjson transition_created_at_unix_ms "$transition_created_at_unix_ms" \
    --argjson reassignment_observed_at_unix_ms "$reassignment_observed_at_unix_ms" \
    '{schema_version:1,aggregate_id:$aggregate_id,event_id:$event_id,payload_type:$payload_type,
      transition_created_at_unix_ms:$transition_created_at_unix_ms,
      reassignment_observed_at_unix_ms:$reassignment_observed_at_unix_ms,
      transition:"post-reassignment Account lifecycle fixture"}' >"$evidence_dir/account-transition.json"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) namespace="${2:?--namespace requires a value}"; shift 2 ;;
    --namespace-run-id) namespace_run_id="${2:?--namespace-run-id requires a value}"; shift 2 ;;
    --context) context="${2:?--context requires a value}"; shift 2 ;;
    --cluster) cluster_name="${2:?--cluster requires a value}"; shift 2 ;;
    --retained-evidence-dir) retained_evidence_dir="${2:?--retained-evidence-dir requires a path}"; shift 2 ;;
    --evidence-dir) evidence_dir="${2:?--evidence-dir requires a path}"; shift 2 ;;
    --deadline-seconds) deadline_seconds="${2:?--deadline-seconds requires a positive integer}"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

[[ -n "$namespace" ]] || { usage >&2; die '--namespace is required'; }
[[ "$namespace" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || die 'namespace is not a valid Kubernetes name'
[[ -n "$namespace_run_id" ]] || { usage >&2; die '--namespace-run-id is required'; }
[[ "$namespace_run_id" =~ ^[A-Za-z0-9._-]+$ ]] || die 'namespace run-id contains unsupported characters'
[[ "$deadline_seconds" =~ ^[1-9][0-9]*$ &&
  "$deadline_seconds" -le "$CONNECT_WORKER_LOSS_MAX_DEADLINE_SECONDS" ]] ||
  die "--deadline-seconds must be a positive integer no greater than $CONNECT_WORKER_LOSS_MAX_DEADLINE_SECONDS"
evidence_dir="${evidence_dir:-out/resilience/connect-worker-loss-$run_id}"
validate_relative_path "$retained_evidence_dir" retained-evidence-dir
validate_relative_path "$evidence_dir" evidence-dir

if [[ "$dry_run" == true ]]; then
  printf 'DRY RUN: cluster=%s context=%s namespace=%s run-id=%s deadline=%ss\n' \
    "$cluster_name" "$context" "$namespace" "$namespace_run_id" "$deadline_seconds"
  printf '%s\n' 'DRY RUN: validate ownership/prerequisites and image cache -> capture task owner -> delete exactly that Connect Pod -> prove reassignment -> verify baseline-aware Account CDC publication.'
  exit 0
fi

for tool in kubectl kind jq curl timeout sed grep date seq sleep tail cat od tr awk cp mv wc; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
simplematch_certification_cdc_verifier_contract_path \
  "$repo_root" "$verifier_contract_script" >/dev/null || die \
  "CDC verifier contract script is missing, symlinked, or not readable: $verifier_contract_script"
mkdir -p "$evidence_dir"
shopt -s nullglob dotglob
existing_evidence=("$evidence_dir"/*)
shopt -u nullglob dotglob
((${#existing_evidence[@]} == 0)) || die "evidence directory must be empty: $evidence_dir"
report_path="$evidence_dir/connect-worker-loss.json"
deadline_at=$((SECONDS + $(connect_worker_loss_setup_deadline_seconds)))
trap cleanup EXIT

validate_cluster
focused_evidence_dir="$retained_evidence_dir"
focused_repo_root="$repo_root"
focused_kubectl_bin=kubectl
focused_preflight_deadline_epoch=$(( $(date +%s) + 60 ))
focused_image_lock="$retained_evidence_dir/local-images.lock"
focused_observer_script="$script_dir/run-risk-cdc-delivery-observer-check.sh"
focused_verifier_observer_copy="$evidence_dir/verifier-observer.sh"
focused_verifier_contract_script="$verifier_contract_script"
focused_verifier_contract_output="$evidence_dir/verifier-contract.log"
focused_verifier_contract_copy="$evidence_dir/verifier-contract.sh"
export SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT="$verifier_contract_script"
simplematch_focused_preflight || die \
  "retained certification preflight failed: $(simplematch_focused_failure_reason)"
[[ "$context" == "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" ]] ||
  die "requested context $context does not match retained context $SIMPLEMATCH_FOCUSED_KIND_CONTEXT"
[[ "$cluster_name" == "${SIMPLEMATCH_FOCUSED_CONTEXT[cluster]}" ]] ||
  die "requested cluster $cluster_name does not match retained cluster ${SIMPLEMATCH_FOCUSED_CONTEXT[cluster]}"
[[ "$namespace" == "${SIMPLEMATCH_FOCUSED_CONTEXT[namespace]}" &&
  "$namespace_run_id" == "${SIMPLEMATCH_FOCUSED_CONTEXT[run_id]}" ]] ||
  die 'worker-loss arguments do not match retained namespace ownership'
write_provenance_evidence
validate_prerequisites
simplematch_kind_image_cache_preflight \
  "$context" "$evidence_dir/connect-deployment.json" \
  "$evidence_dir/image-cache-preflight.json" ||
  die 'Kafka Connect image is missing or not executable on every eligible kind worker'
start_connect_port_forward

postgres_pod="$(kns get pods -l app.kubernetes.io/name=postgres -o json \
  | jq -er '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))][0].metadata.name')" ||
  die 'no Ready PostgreSQL Pod is available for the outbox fixture'

export CDC_KAFKA_BOOTSTRAP=kafka:9092
export CDC_VERIFIER_TIMEOUT_SECONDS=30
export CDC_VERIFIER_POLL_INTERVAL_SECONDS=1
export CDC_VERIFIER_SCAN_TIMEOUT_MS=2000
CDC_KAFKA_EXEC=(kafka_exec)
CDC_OUTBOX_EXEC=(postgres_exec)
CDC_CONNECT_STATUS_EXEC=(connect_status)

wait_connector_running account-service-outbox "$evidence_dir/connect-status-before.json"
wait_connector_running risk-service-outbox "$evidence_dir/risk-connect-status-before.json"
kns get pods -l app.kubernetes.io/name=kafka-connect,app.kubernetes.io/component=connector \
  -o json >"$evidence_dir/connect-pods-before.json"
connect_worker_loss_pods_are_valid "$evidence_dir/connect-pods-before.json" || die 'Connect Pod baseline failed validation'
connect_worker_loss_target_identity "$evidence_dir/connect-status-before.json" \
  "$evidence_dir/connect-pods-before.json" "$evidence_dir/task-owner-before.json" ||
  die 'Account connector task owner could not be mapped to one Ready Connect Pod'
capture_target_slot "$evidence_dir/task-owner-before.json"

target_pod="$(jq -er '.pod' "$evidence_dir/task-owner-before.json")"
target_uid="$(jq -er '.pod_uid' "$evidence_dir/task-owner-before.json")"
recheck_target_before_delete
recovery_deadline_started_at_unix_ms="$(date +%s%3N)"
deadline_at=$((SECONDS + deadline_seconds))
delete_target_pod
wait_for_reassignment "$evidence_dir/connect-status-before.json" \
  "$evidence_dir/task-owner-before.json" "$target_pod" "$target_uid"
wait_connector_running risk-service-outbox "$evidence_dir/risk-connect-status-after.json"
reassignment_observed_at_unix_ms="$(( $(date +%s) * 1000 ))"
run_publication_check "$reassignment_observed_at_unix_ms"
cleanup_fixture || die 'could not clean up the run-owned Account outbox fixture'

before_target_json="$(cat "$evidence_dir/task-owner-before.json")"
after_target_json="$(cat "$evidence_dir/task-owner-after-reassignment.json")"
jq -n \
  --argjson schema_version "$CONNECT_WORKER_LOSS_REPORT_SCHEMA_VERSION" \
  --arg cluster "$cluster_name" --arg context "$context" --arg namespace "$namespace" \
  --arg namespace_run_id "$namespace_run_id" --arg run_id "$run_id" \
  --argjson deadline_seconds "$deadline_seconds" \
  --argjson recovery_deadline_started_at_unix_ms "$recovery_deadline_started_at_unix_ms" \
  --argjson before "$before_target_json" --argjson after "$after_target_json" \
  --arg event_id "$(jq -r '.event_id' "$evidence_dir/account-outbox-probe.json")" \
  '{schema_version:$schema_version,profile:"connect-worker-loss",status:"PASSED",
    cluster:$cluster,context:$context,namespace:$namespace,namespace_run_id:$namespace_run_id,
    run_id:$run_id,fault_mode:"pod-delete",deadline_seconds:$deadline_seconds,
    recovery_deadline_started_at_unix_ms:$recovery_deadline_started_at_unix_ms,
    prerequisites:{connect_workers:2,ready_workers_before:2,ready_workers_after:2,
      internal_topics_rf3:true,pdb_min_available_1:true,connect_has_no_pvc:true,
      service_owned_connectors:true,flyway_and_topic_prerequisites:true},
    task_reassignment:{connector:"account-service-outbox",task_id:$after.task_id,
      before:$before,after:$after,task_id_unchanged:($before.task_id == $after.task_id),
      worker_id_changed:($before.worker_id != $after.worker_id),
      pod_uid_changed:($before.pod_uid != $after.pod_uid),
      node_changed:($before.node != $after.node)},
    publication:{baseline_captured:true,post_transition_probe:true,exact_kafka_record:true,
      transition_after_reassignment:true,transition_file:"account-transition.json",
      event_id:$event_id,baseline_file:"account-outbox-baseline.json",
      probe_file:"account-outbox-probe.json",kafka_baseline_file:"account-kafka-baseline.tsv",
      publication_evidence_file:"account-publication.json"},
    evidence:{status_before_file:"connect-status-before.json",
      status_after_file:"connect-status-after-reassignment.json",
      pods_before_file:"connect-pods-before.json",
      pods_after_file:"connect-pods-after-reassignment.json",
      target_before_file:"task-owner-before.json",
      target_after_file:"task-owner-after-reassignment.json",
      status_pre_delete_file:"connect-status-before-delete.json",
      pods_pre_delete_file:"connect-pods-before-delete.json",
      target_pre_delete_file:"task-owner-before-delete.json",
      pod_pre_delete_file:"pod-pre-delete.json",
      pod_patch_file:"pod-patch.json",
      pod_delete_precondition_file:"pod-delete-precondition.json",
      image_cache_preflight_file:"image-cache-preflight.json",
      target_delete_observation_file:"target-delete-observation.json",
      worker_loss_file:"worker-loss.json",provenance_file:"provenance.json",
      verifier_contract_file:"verifier-contract.log",
      verifier_contract_script_file:"verifier-contract.sh",
      verifier_observer_script_file:"verifier-observer.sh",
      transition_file:"account-transition.json",
      baseline_file:"account-outbox-baseline.json",probe_file:"account-outbox-probe.json",
      kafka_baseline_file:"account-kafka-baseline.tsv",
      publication_evidence_file:"account-publication.json",
      nodes_file:"nodes.json",
      control_plane_readyz_file:"control-plane/readyz.txt",
      control_plane_before_file:"control-plane/before.json",
      control_plane_after_file:"control-plane/after.json",
      control_plane_events_file:"control-plane/events.json",
      connect_deployment_file:"connect-deployment.json",
      connect_pdb_file:"connect-pdb.json",
      connect_config_file:"connect-config.json",
      account_connector_file:"account-service-outbox-configmap.json",
      risk_connector_file:"risk-service-outbox-configmap.json",postgres_file:"prerequisites/postgres.json",
      topic_provisioning_file:"prerequisites/kafka-topic-provisioning.json",
      account_flyway_file:"prerequisites/account-service-flyway.json",
      risk_flyway_file:"prerequisites/risk-service-flyway.json",
      persistence_flyway_file:"prerequisites/persistence-flyway.json",
      market_data_projection_flyway_file:"prerequisites/market-data-projection-flyway.json",
      query_flyway_file:"prerequisites/query-service-flyway.json",
      quickfix_gateway_flyway_file:"prerequisites/quickfix-gateway-flyway.json",
      connect_configs_topic_file:"prerequisites/simplematch-connect-configs.txt",
      connect_offsets_topic_file:"prerequisites/simplematch-connect-offsets.txt",
      connect_status_topic_file:"prerequisites/simplematch-connect-status.txt"},
    failure_reason:null,
    claim_boundary:["local Kafka Connect task reassignment after one task-owning Pod loss",
      "local Account outbox -> Debezium -> account.lifecycle exact publication",
      "at-least-once transport observation; not production HA"]}' \
  >"$report_path"
connect_worker_loss_report_is_passed "$report_path" || die 'worker-loss report failed its evidence contract'

printf 'Connect worker-loss diagnostic passed: %s\n' "$report_path"

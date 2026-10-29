#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

# Focused Kafka Connect worker-loss scenario Module. Its execution Interface
# is connect_worker_loss_run; the CLI request Adapter stays at the entrypoint.

connect_worker_loss_lib_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$connect_worker_loss_lib_dir/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$connect_worker_loss_lib_dir/local-kind.sh"
# shellcheck source=scripts/lib/local-resilience.sh
source "$connect_worker_loss_lib_dir/local-resilience.sh"
# shellcheck source=scripts/lib/connect-rest-tunnel.sh
source "$connect_worker_loss_lib_dir/connect-rest-tunnel.sh"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$connect_worker_loss_lib_dir/local-certification-provenance.sh"
# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$connect_worker_loss_lib_dir/local-certification-phase-graph.sh"
# shellcheck source=scripts/lib/local-certification-evidence.sh
source "$connect_worker_loss_lib_dir/local-certification-evidence.sh"
# shellcheck source=scripts/lib/local-certification-images.sh
source "$connect_worker_loss_lib_dir/local-certification-images.sh"
# shellcheck source=scripts/lib/local-certification-focused-diagnostic.sh
source "$connect_worker_loss_lib_dir/local-certification-focused-diagnostic.sh"
# shellcheck source=scripts/lib/cdc-verifier.sh
source "$connect_worker_loss_lib_dir/cdc-verifier.sh"
# shellcheck source=scripts/lib/connect-worker-loss.sh
source "$connect_worker_loss_lib_dir/connect-worker-loss.sh"
unset connect_worker_loss_lib_dir

: "${CONNECT_WORKER_LOSS_KUBECTL_BIN:=kubectl}"
: "${CONNECT_WORKER_LOSS_KIND_BIN:=kind}"
: "${CONNECT_WORKER_LOSS_CURL_BIN:=curl}"
declare -gA CONNECT_WORKER_LOSS_FOCUSED_CONFIG=()

connect_worker_loss_configure_adapters() {
  local kubectl_bin="$1" kind_bin="$2" curl_bin="$3"

  [[ -n "$kubectl_bin" && -n "$kind_bin" && -n "$curl_bin" ]] || return 2
  CONNECT_WORKER_LOSS_KUBECTL_BIN="$kubectl_bin"
  CONNECT_WORKER_LOSS_KIND_BIN="$kind_bin"
  CONNECT_WORKER_LOSS_CURL_BIN="$curl_bin"
  SIMPLEMATCH_KIND_KUBECTL_BIN="$kubectl_bin"
  SIMPLEMATCH_KIND_BIN="$kind_bin"
  CONNECT_REST_TUNNEL_KUBECTL_BIN="$kubectl_bin"
  CONNECT_REST_TUNNEL_CURL_BIN="$curl_bin"
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
  run_bounded "$CONNECT_WORKER_LOSS_KUBECTL_BIN" --context "$context" "$@"
}

kns() {
  kube -n "$namespace" "$@"
}

short_cleanup() {
  timeout --foreground 30s "$CONNECT_WORKER_LOSS_KUBECTL_BIN" \
    --context "$context" -n "$namespace" "$@"
}

write_failure_report() {
  local reason="${failure_reason:-diagnostic did not complete}"
  jq -n \
    --argjson schema_version "$report_schema_version" \
    --arg status FAILED --arg cluster "$cluster_name" --arg context "$context" \
    --arg namespace "$namespace" --arg namespace_run_id "$namespace_run_id" \
    --arg run_id "$run_id" --argjson deadline_seconds "$deadline_seconds" \
    --arg reason "$reason" \
    '{schema_version:$schema_version,profile:"connect-worker-loss",status:$status,
      cluster:$cluster,context:$context,namespace:$namespace,
      namespace_run_id:$namespace_run_id,run_id:$run_id,fault_mode:"pod-delete",
      deadline_seconds:$deadline_seconds,recovery_deadline_started_at_unix_ms:0,
      prerequisites:{},task_reassignment:{},
      publication:{},failure_reason:$reason}' \
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
  connect_rest_tunnel_close 5 || cleanup_status=1
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
  local current_commit retained_image
  current_commit="$(git -C "$repo_root" rev-parse HEAD)" || die 'could not record current source'
  retained_image="$(simplematch_certification_verifier_image_identity "$retained_evidence_dir")" ||
    die 'retained verifier image identity is missing'
  jq -n --arg cluster "$cluster_name" --arg context "$context" \
    --arg namespace "$namespace" --arg namespace_run_id "$namespace_run_id" \
    --arg run_id "$run_id" --arg commit "$current_commit" --arg image "$retained_image" \
    --argjson deadline "$deadline_seconds" \
    --arg runtime "$SIMPLEMATCH_FOCUSED_CURRENT_CDC_RUNTIME_SIGNATURE" \
    --arg retained_runtime "$SIMPLEMATCH_FOCUSED_RETAINED_CDC_RUNTIME_SIGNATURE" \
    --arg verifier "$SIMPLEMATCH_FOCUSED_CURRENT_CDC_VERIFIER_SIGNATURE" \
    --arg retained_verifier "$SIMPLEMATCH_FOCUSED_RETAINED_CDC_VERIFIER_SIGNATURE" \
    '{status:"PASS",cluster:$cluster,context:$context,namespace:$namespace,
      namespace_run_id:$namespace_run_id,run_id:$run_id,current_commit:$commit,
      deadline_seconds:$deadline,verifier_image_identity:$image,runtime_reused:true,
      cdc_runtime_signature:$runtime,retained_cdc_runtime_signature:$retained_runtime,
      cdc_verifier_signature:$verifier,retained_cdc_verifier_signature:$retained_verifier}' \
    >"$evidence_dir/provenance.json" || die 'could not record focused preflight identity'
}

validate_cluster() {
  local contexts nodes_json worker_count ready_workers
  contexts="$(run_bounded "$CONNECT_WORKER_LOSS_KUBECTL_BIN" config get-contexts -o name)" || die 'could not inspect Kubernetes contexts'
  grep -Fxq "$context" <<<"$contexts" || die "Kubernetes context is not configured: $context"
  run_bounded "$CONNECT_WORKER_LOSS_KIND_BIN" get clusters | grep -Fxq "$cluster_name" ||
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
  local output="$evidence_dir/prerequisites/${name}.json"

  kns get "$kind" "$name" -o json >"$output" ||
    die "required prerequisite is missing: $kind/$name"
}

validate_prerequisites() {
  local service connector topic description configuration
  mkdir -p "$evidence_dir/prerequisites"
  capture_prerequisite kafka-topic-provisioning job
  for service in account-service risk-service; do
    capture_prerequisite "$service-flyway" job
  done
  capture_prerequisite postgres statefulset
  kns rollout status statefulset/postgres --timeout="$(remaining_seconds)s" >/dev/null ||
    die 'PostgreSQL was not Ready before Connect observation'

  kns get deployment kafka-connect -o json >"$evidence_dir/connect-deployment.json" ||
    die 'Kafka Connect Deployment is missing'
  kns get pods -l app.kubernetes.io/name=kafka-connect,app.kubernetes.io/component=connector \
    -o json >"$evidence_dir/connect-pods-before.json" ||
    die 'could not capture Kafka Connect Pods'

  for connector in account-service-outbox risk-service-outbox; do
    kns get configmap "$connector-connector" -o json \
      >"$evidence_dir/${connector}-configmap.json" ||
      die "service-owned connector ConfigMap is missing: $connector"
  done

  kafka_pod="$(kns get pods -l app.kubernetes.io/name=kafka,app.kubernetes.io/component=broker \
    -o json | jq -er '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))][0].metadata.name')" ||
    die 'no Ready Kafka Pod is available for internal topic checks'
  for topic in simplematch-connect-configs simplematch-connect-offsets simplematch-connect-status; do
    description="$(kafka_exec /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
      --describe --topic "$topic")" || die "could not inspect Kafka topic $topic"
    configuration="$(kafka_exec /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka:9092 \
      --entity-type topics --entity-name "$topic" --describe --all)" ||
      die "could not inspect Kafka topic configuration $topic"
    {
      printf '%s\n' "### $topic"
      printf '%s\n' "$description" "$configuration"
    } >"$evidence_dir/prerequisites/$topic.txt"
  done
}

connect_status() {
  local connector="$1" remaining
  remaining="$(remaining_seconds)"
  ((remaining > 0)) || return 124
  connect_rest_tunnel_status "$connector" "$remaining"
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
      [[ -z "$observed_uid" || "$observed_uid" == "$target_uid" ]] ||
        die 'a different Pod now uses the deleted target name'
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
  connect_worker_loss_delete_output_is_exact "$target_pod" "$delete_output" ||
    die 'Pod deletion selector did not report exactly the intended task-owning Pod'
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
        selector_target_count:1,delete_output_contains_target:true,target_uid_absent:true,
        recovery_deadline_started_at_unix_ms:$recovery_started_at_unix_ms,
        requested_at_unix_ms:$requested_at_unix_ms,
        requested_at:$requested_at}' >"$evidence_dir/worker-loss.json"
}

connect_worker_loss_delete_output_is_exact() {
  local target_pod="$1" output="$2" line
  local -a deleted_resources

  mapfile -t deleted_resources <<<"$output"
  ((${#deleted_resources[@]} == 1)) || return 1
  line="${deleted_resources[0]}"
  [[ "$line" == "pod/$target_pod deletion requested" ||
    "$line" == "pod/$target_pod deleted" ||
    "$line" == "pod \"$target_pod\" deleted from "*" namespace" ]]
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
  now_ms="$(date +%s%3N)"
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
  transition_created_at_unix_ms="$(date +%s%3N)"
  (( transition_created_at_unix_ms > reassignment_observed_at_unix_ms )) ||
    die 'clock did not advance after Connect reassignment'
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

write_passed_report() {
  local temporary="$report_path.tmp"
  resilience_verify_evidence connect draft "$evidence_dir" >"$temporary" ||
    die 'could not assemble the Connect report'
  connect_worker_loss_report_is_passed "$temporary" || die 'Connect evidence was rejected'
  mv -- "$temporary" "$report_path" || die 'could not publish the Connect report'
}

connect_worker_loss_preflight() {
  validate_cluster
  CONNECT_WORKER_LOSS_FOCUSED_CONFIG=(
    [evidence_dir]="$retained_evidence_dir"
    [repo_root]="$repo_root"
    [kubectl_bin]="$CONNECT_WORKER_LOSS_KUBECTL_BIN"
    [preflight_deadline_epoch]="$(( $(date +%s) + 60 ))"
    [image_lock]="$retained_evidence_dir/local-images.lock"
    [observer_script]="$script_dir/run-risk-cdc-delivery-observer-check.sh"
    [verifier_observer_copy]="$evidence_dir/verifier-observer.sh"
    [verifier_contract_script]="$verifier_contract_script"
    [verifier_contract_output]="$evidence_dir/verifier-contract.log"
    [verifier_contract_copy]="$evidence_dir/verifier-contract.sh"
  )
  simplematch_focused_configure_inputs CONNECT_WORKER_LOSS_FOCUSED_CONFIG || die \
    'focused diagnostic adapter configuration is invalid'
  [[ "${CONNECT_WORKER_LOSS_FOCUSED_CONFIG[evidence_dir]}" == "$focused_evidence_dir" ]] ||
    die 'focused diagnostic adapter configuration was not applied'
  export SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT="$verifier_contract_script"
  simplematch_focused_preflight || die \
    "retained certification preflight failed: $(simplematch_focused_failure_reason)"
  [[ "$context" == "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" ]] || die \
    "requested context $context does not match retained context $SIMPLEMATCH_FOCUSED_KIND_CONTEXT"
  [[ "$cluster_name" == "${SIMPLEMATCH_FOCUSED_CONTEXT[cluster]}" ]] || die \
    "requested cluster $cluster_name does not match retained cluster ${SIMPLEMATCH_FOCUSED_CONTEXT[cluster]}"
  [[ "$namespace" == "${SIMPLEMATCH_FOCUSED_CONTEXT[namespace]}" &&
    "$namespace_run_id" == "${SIMPLEMATCH_FOCUSED_CONTEXT[run_id]}" ]] ||
    die 'worker-loss arguments do not match retained namespace ownership'
  write_provenance_evidence
  validate_prerequisites
  simplematch_kind_image_cache_preflight "$context" \
    "$evidence_dir/connect-deployment.json" "$evidence_dir/image-cache-preflight.json" ||
    die 'Kafka Connect image is missing or not executable on every eligible kind worker'
  resilience_verify_evidence connect prerequisites "$evidence_dir" >/dev/null ||
    die 'Kafka Connect prerequisite evidence failed typed verification'
  connect_rest_tunnel_configure "$context" "$namespace" \
    "$evidence_dir/connect-port-forward.log" ||
    die 'Kafka Connect REST tunnel configuration is invalid'
}

connect_worker_loss_observe_owner() {
  postgres_pod="$(kns get pods -l app.kubernetes.io/name=postgres -o json |
    jq -er '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))][0].metadata.name')" ||
    die 'no Ready PostgreSQL Pod is available for the outbox fixture'
  export CDC_KAFKA_BOOTSTRAP=kafka:9092 CDC_VERIFIER_TIMEOUT_SECONDS=30
  export CDC_VERIFIER_POLL_INTERVAL_SECONDS=1 CDC_VERIFIER_SCAN_TIMEOUT_MS=2000
  cdc_configure_adapters kafka_exec postgres_exec connect_status
  wait_connector_running account-service-outbox "$evidence_dir/connect-status-before.json"
  wait_connector_running risk-service-outbox "$evidence_dir/risk-connect-status-before.json"
  kns get pods -l \
    app.kubernetes.io/name=kafka-connect,app.kubernetes.io/component=connector \
    -o json >"$evidence_dir/connect-pods-before.json"
  connect_worker_loss_target_identity "$evidence_dir/connect-status-before.json" \
    "$evidence_dir/connect-pods-before.json" "$evidence_dir/task-owner-before.json" ||
    die 'Account connector task owner could not be mapped to one Ready Connect Pod'
  capture_target_slot "$evidence_dir/task-owner-before.json"
}

connect_worker_loss_arm_target() {
  target_pod="$(jq -er '.pod' "$evidence_dir/task-owner-before.json")"
  target_uid="$(jq -er '.pod_uid' "$evidence_dir/task-owner-before.json")"
  recheck_target_before_delete
}

connect_worker_loss_delete_target() {
  recovery_deadline_started_at_unix_ms="$(date +%s%3N)"
  deadline_at=$((SECONDS + deadline_seconds))
  delete_target_pod
}

connect_worker_loss_observe_reassignment() {
  wait_for_reassignment "$evidence_dir/connect-status-before.json" \
    "$evidence_dir/task-owner-before.json" "$target_pod" "$target_uid"
  wait_connector_running risk-service-outbox "$evidence_dir/risk-connect-status-after.json"
  reassignment_observed_at_unix_ms="$(date +%s%3N)"
}

connect_worker_loss_run() (
  local script_dir repo_root
  local cluster_name context namespace namespace_run_id evidence_dir retained_evidence_dir
  local deadline_seconds report_schema_version verifier_contract_script dry_run run_id deadline_at
  local failure_reason report_path postgres_pod kafka_pod fixture_aggregate_id
  local fixture_created target_pod target_uid recovery_deadline_started_at_unix_ms
  local fault_requested_at_unix_ms reassignment_observed_at_unix_ms
  local account_id="" account_payload_type=""
  local -a existing_evidence=()

  [[ "${CONNECT_WORKER_LOSS_REQUEST[help]-false}" != true ]] || return 0
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
  repo_root="$(cd -- "$script_dir/.." && pwd)"
  cluster_name="${CONNECT_WORKER_LOSS_REQUEST[cluster]}"
  context="${CONNECT_WORKER_LOSS_REQUEST[context]}"
  namespace="${CONNECT_WORKER_LOSS_REQUEST[namespace]}"
  namespace_run_id="${CONNECT_WORKER_LOSS_REQUEST[namespace_run_id]}"
  evidence_dir="${CONNECT_WORKER_LOSS_REQUEST[evidence_dir]}"
  retained_evidence_dir="${CONNECT_WORKER_LOSS_REQUEST[retained_evidence_dir]}"
  deadline_seconds="${CONNECT_WORKER_LOSS_REQUEST[deadline_seconds]}"
  dry_run="${CONNECT_WORKER_LOSS_REQUEST[dry_run]}"
  run_id="${CONNECT_WORKER_LOSS_REQUEST[run_id]}"
  verifier_contract_script="${CONNECT_WORKER_LOSS_REQUEST[verifier_contract_script]:-$script_dir/test-cdc-observer-fixture-contract.sh}"
  report_schema_version="$(connect_worker_loss_report_schema_version)"
  deadline_at=0
  failure_reason=""
  report_path=""
  postgres_pod=""
  kafka_pod=""
  fixture_aggregate_id=""
  fixture_created=false
  target_pod=""
  target_uid=""
  recovery_deadline_started_at_unix_ms=0
  fault_requested_at_unix_ms=0
  reassignment_observed_at_unix_ms=0

  evidence_dir="${evidence_dir:-out/resilience/connect-worker-loss-$run_id}"
  validate_relative_path "$retained_evidence_dir" retained-evidence-dir
  validate_relative_path "$evidence_dir" evidence-dir

  if [[ "$dry_run" == true ]]; then
    printf 'DRY RUN: cluster=%s context=%s namespace=%s run-id=%s deadline=%ss\n' \
      "$cluster_name" "$context" "$namespace" "$namespace_run_id" "$deadline_seconds"
    printf '%s\n' 'DRY RUN: validate ownership/prerequisites and image cache -> capture task owner -> delete exactly that Connect Pod -> prove reassignment -> verify baseline-aware Account CDC publication.'
    printf '%s\n' 'DRY RUN: output is diagnostic evidence only, never a full-local certification PASS.'
    return 0
  fi

  connect_worker_loss_configure_adapters \
    "$CONNECT_WORKER_LOSS_KUBECTL_BIN" "$CONNECT_WORKER_LOSS_KIND_BIN" \
    "$CONNECT_WORKER_LOSS_CURL_BIN" || die 'worker-loss adapter configuration is invalid'

  for tool in jq ruby timeout sed grep date seq sleep tail cat od tr awk cp mv wc; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
  done
  command -v "$CONNECT_WORKER_LOSS_KUBECTL_BIN" >/dev/null 2>&1 ||
    die "$CONNECT_WORKER_LOSS_KUBECTL_BIN is required"
  command -v "$CONNECT_WORKER_LOSS_KIND_BIN" >/dev/null 2>&1 ||
    die "$CONNECT_WORKER_LOSS_KIND_BIN is required"
  command -v "$CONNECT_WORKER_LOSS_CURL_BIN" >/dev/null 2>&1 ||
    die "$CONNECT_WORKER_LOSS_CURL_BIN is required"
  simplematch_certification_cdc_verifier_contract_path \
    "$repo_root" "$verifier_contract_script" >/dev/null || die \
    "CDC verifier contract script is missing, symlinked, or not readable: $verifier_contract_script"
  mkdir -p "$evidence_dir"
  shopt -s nullglob dotglob
  existing_evidence=("$evidence_dir"/*)
  shopt -u nullglob dotglob
  ((${#existing_evidence[@]} == 0)) || die "evidence directory must be empty: $evidence_dir"
  report_path="$evidence_dir/connect-worker-loss.json"
  trap cleanup EXIT
  resilience_prepare_evidence_verifier "$repo_root" || die 'could not prepare recovery verifier'
  deadline_at=$((SECONDS + $(connect_worker_loss_setup_deadline_seconds)))

  connect_worker_loss_preflight || return 1
  connect_worker_loss_observe_owner || return 1
  connect_worker_loss_arm_target || return 1
  connect_worker_loss_delete_target || return 1
  connect_worker_loss_observe_reassignment || return 1
  run_publication_check "$reassignment_observed_at_unix_ms" || return 1
  cleanup_fixture || die 'could not clean up the run-owned Account outbox fixture'
  connect_rest_tunnel_close 5 || die 'could not close the Connect REST tunnel'
  write_passed_report || return 1

  printf 'Connect worker-loss diagnostic passed: %s\n' "$report_path"
)

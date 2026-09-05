#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/cdc-verifier.sh
source "$script_dir/lib/cdc-verifier.sh"
# shellcheck source=scripts/lib/connect-worker-loss.sh
source "$script_dir/lib/connect-worker-loss.sh"

fail() {
  printf 'Connect worker-loss contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-connect-worker-loss.XXXXXX")"
trap 'rm -rf -- "$fixture_dir"' EXIT

status_before="$fixture_dir/status-before.json"
status_after="$fixture_dir/status-after.json"
status_running_only="$fixture_dir/status-running-only.json"
pods_before="$fixture_dir/pods-before.json"
pods_after="$fixture_dir/pods-after.json"
target_before="$fixture_dir/target-before.json"
target_after="$fixture_dir/target-after.json"
report="$fixture_dir/report.json"
pre_delete_status="$fixture_dir/status-pre-delete.json"
pre_delete_pods="$fixture_dir/pods-pre-delete.json"
pre_delete_target="$fixture_dir/target-pre-delete.json"
pre_delete_pod="$fixture_dir/pod-pre-delete.json"
pod_patch="$fixture_dir/pod-patch.json"
pod_delete_precondition="$fixture_dir/pod-delete-precondition.json"
worker_loss="$fixture_dir/worker-loss.json"
provenance="$fixture_dir/provenance.json"
baseline="$fixture_dir/account-outbox-baseline.json"
probe="$fixture_dir/account-outbox-probe.json"
kafka_baseline="$fixture_dir/account-kafka-baseline.tsv"
publication_evidence="$fixture_dir/account-publication.json"
transition="$fixture_dir/account-transition.json"
nodes="$fixture_dir/nodes.json"
deployment="$fixture_dir/connect-deployment.json"
pdb="$fixture_dir/connect-pdb.json"
account_config="$fixture_dir/account-service-outbox-configmap.json"
risk_config="$fixture_dir/risk-service-outbox-configmap.json"
postgres="$fixture_dir/postgres.json"
topic_provisioning="$fixture_dir/topic-provisioning.json"
account_flyway="$fixture_dir/account-flyway.json"
risk_flyway="$fixture_dir/risk-flyway.json"
persistence_flyway="$fixture_dir/persistence-flyway.json"
market_data_projection_flyway="$fixture_dir/market-data-projection-flyway.json"
query_flyway="$fixture_dir/query-flyway.json"
quickfix_gateway_flyway="$fixture_dir/quickfix-gateway-flyway.json"
connect_configs_topic="$fixture_dir/connect-configs.txt"
connect_offsets_topic="$fixture_dir/connect-offsets.txt"
connect_status_topic="$fixture_dir/connect-status.txt"
control_plane_dir="$fixture_dir/control-plane"

jq -n '{name:"account-service-outbox",connector:{state:"RUNNING",worker_id:"10.244.0.11:8083"},tasks:[{id:0,state:"RUNNING",worker_id:"10.244.0.11:8083"}]}' >"$status_before"
jq -n '{name:"account-service-outbox",connector:{state:"RUNNING",worker_id:"10.244.0.33:8083"},tasks:[{id:0,state:"RUNNING",worker_id:"10.244.0.33:8083"}]}' >"$status_after"
jq -n '{name:"account-service-outbox",connector:{state:"RUNNING",worker_id:"10.244.0.11:8083"},tasks:[]}' >"$status_running_only"

cat >"$pods_before" <<'EOF_PODS'
{"items":[
  {"metadata":{"name":"kafka-connect-a","uid":"uid-a","labels":{"app.kubernetes.io/name":"kafka-connect","app.kubernetes.io/component":"connector"}},"spec":{"nodeName":"simplematch-live-worker","volumes":[{"name":"runtime-tmp","emptyDir":{}}]},"status":{"podIP":"10.244.0.11","conditions":[{"type":"Ready","status":"True"}]}},
  {"metadata":{"name":"kafka-connect-b","uid":"uid-b","labels":{"app.kubernetes.io/name":"kafka-connect","app.kubernetes.io/component":"connector"}},"spec":{"nodeName":"simplematch-live-worker2","volumes":[{"name":"runtime-tmp","emptyDir":{}}]},"status":{"podIP":"10.244.0.22","conditions":[{"type":"Ready","status":"True"}]}}
]}
EOF_PODS
cat >"$pods_after" <<'EOF_PODS'
{"items":[
  {"metadata":{"name":"kafka-connect-b","uid":"uid-b","labels":{"app.kubernetes.io/name":"kafka-connect","app.kubernetes.io/component":"connector"}},"spec":{"nodeName":"simplematch-live-worker2","volumes":[{"name":"runtime-tmp","emptyDir":{}}]},"status":{"podIP":"10.244.0.22","conditions":[{"type":"Ready","status":"True"}]}},
  {"metadata":{"name":"kafka-connect-c","uid":"uid-c","labels":{"app.kubernetes.io/name":"kafka-connect","app.kubernetes.io/component":"connector"}},"spec":{"nodeName":"simplematch-live-worker3","volumes":[{"name":"runtime-tmp","emptyDir":{}}]},"status":{"podIP":"10.244.0.33","conditions":[{"type":"Ready","status":"True"}]}}
]}
EOF_PODS

connect_worker_loss_status_is_valid "$status_before" || fail 'valid before status was rejected'
connect_worker_loss_status_is_valid "$status_after" || fail 'valid after status was rejected'
if connect_worker_loss_status_is_valid "$status_running_only"; then
  fail 'REST RUNNING without a task was accepted'
fi
connect_worker_loss_pods_are_valid "$pods_before" || fail 'valid before Pods were rejected'
connect_worker_loss_pods_are_valid "$pods_after" || fail 'valid after Pods were rejected'

connect_worker_loss_target_identity "$status_before" "$pods_before" "$target_before" ||
  fail 'before task owner could not be resolved'
connect_worker_loss_target_identity "$status_after" "$pods_after" "$target_after" ||
  fail 'after task owner could not be resolved'
jq '.worker_slot = "0"' "$target_before" >"$target_before.tmp" && mv "$target_before.tmp" "$target_before"
jq '.worker_slot = "2"' "$target_after" >"$target_after.tmp" && mv "$target_after.tmp" "$target_after"
[[ "$(jq -r '.pod' "$target_before")" == kafka-connect-a ]] || fail 'wrong before task owner'
[[ "$(jq -r '.pod' "$target_after")" == kafka-connect-c ]] || fail 'wrong after task owner'
connect_worker_loss_assert_reassignment "$status_before" "$status_after" "$target_before" "$target_after" ||
  fail 'valid reassignment was rejected'

cp "$status_before" "$pre_delete_status"
cp "$pods_before" "$pre_delete_pods"
cp "$target_before" "$pre_delete_target"
jq '.items[0]' "$pods_before" >"$pre_delete_pod"
jq '.metadata.labels["simplematch.io/worker-loss-run"] = "connect-worker-loss-run-1"' \
  "$pre_delete_pod" >"$pod_patch"
jq '.metadata.labels["simplematch.io/worker-loss-run"] = "connect-worker-loss-run-1"' \
  "$pre_delete_pod" >"$pod_delete_precondition"
jq -n '{fault:"pod-delete",target_pod:"kafka-connect-a",target_pod_uid:"uid-a",
  delete_selector:"simplematch.io/worker-loss-run=connect-worker-loss-run-1",
  delete_field_selector:"metadata.uid=uid-a",
  delete_requested:true,uid_precondition_test:true,pre_delete_recheck:true,
  delete_output_contains_target:true,target_uid_absent:true}' >"$worker_loss"
jq -n '{status:"PASS",namespace:"simplematch-cert-run",namespace_run_id:"run-1",
  current_commit:"0000000000000000000000000000000000000000",
  cdc_runtime_signature:"0000000000000000000000000000000000000000000000000000000000000000",
  cdc_verifier_signature:"1111111111111111111111111111111111111111111111111111111111111111",
  verifier_image_identity:"sha256:2222222222222222222222222222222222222222222222222222222222222222"}' >"$provenance"
jq -n '{schema_version:1,schema:"account_service",aggregate_type:"account_reservation",
  aggregate_id:"connect-worker-loss-run-1-reservation",event_ids:["00000000-0000-7000-8000-000000000002"]}' \
  >"$baseline"
payload_hex=6869
payload_sha256="$(printf '%s' "$payload_hex" | xxd -r -p | sha256sum | awk '{print $1}')"
jq -n --arg sha "$payload_sha256" \
  '{event_id:"00000000-0000-7000-8000-000000000001",
    business_identity:"connect-worker-loss-run-1-reservation",message_key:"account-1",
    topic:"account.lifecycle",payload_hex:"6869",payload_sha256:$sha,
    payload_type:"simplematch.account.v2.AccountLifecycleEvent",created_at_unix_ms:2,
    headers_json:"{\"event_id\":\"00000000-0000-7000-8000-000000000001\"}",
    explicit_partition:null}' >"$probe"
printf '%s\n' '0	0' '1	1' >"$kafka_baseline"
jq -n --arg event_id "00000000-0000-7000-8000-000000000001" \
  --arg aggregate_id "connect-worker-loss-run-1-reservation" \
  --arg payload_type "simplematch.account.v2.AccountLifecycleEvent" \
  '{schema_version:1,aggregate_id:$aggregate_id,event_id:$event_id,
    payload_type:$payload_type,transition_created_at_unix_ms:2,
    reassignment_observed_at_unix_ms:1,
    transition:"post-reassignment Account lifecycle fixture"}' >"$transition"
jq -n --arg event_id "00000000-0000-7000-8000-000000000001" \
  --arg headers_sha "$(printf '%s' '{"event_id":"00000000-0000-7000-8000-000000000001"}' |
    sha256sum | awk '{print $1}')" \
  --arg payload_sha "$payload_sha256" \
  '{schema_version:1,status:"PASS",topic:"account.lifecycle",event_id:$event_id,
    partition:1,offset:2,expected_message_key:"account-1",
    expected_timestamp_unix_ms:2,expected_headers_json_sha256:$headers_sha,
    expected_event_type:"simplematch.account.v2.AccountLifecycleEvent",
    expected_payload_sha256:$payload_sha,
    verification:{headers_exact:true,key_exact:true,timestamp_exact:true,payload_exact:true}}' \
  >"$publication_evidence"
jq -n '{items:[
  {metadata:{labels:{"simplematch.io/node-pool":"local-resilience"}},status:{conditions:[{type:"Ready",status:"True"}]}},
  {metadata:{labels:{"simplematch.io/node-pool":"local-resilience"}},status:{conditions:[{type:"Ready",status:"True"}]}}
]}' >"$nodes"
jq -n '{spec:{replicas:2,template:{spec:{volumes:[{name:"tmp",emptyDir:{}}],containers:[{name:"kafka-connect",image:"quay.io/debezium/connect:3.6.0.Final"}]}}}}' >"$deployment"
jq -n '{spec:{minAvailable:1,selector:{matchLabels:{"app.kubernetes.io/name":"kafka-connect","app.kubernetes.io/component":"connector"}}}}' >"$pdb"
for connector in account-service-outbox risk-service-outbox; do
  table='account_service.outbox'
  config_file="$account_config"
  if [[ "$connector" == risk-service-outbox ]]; then
    table='risk_service.outbox'
    config_file="$risk_config"
  fi
  jq -n --arg connector "$connector" --arg table "$table" \
    '{data:{"connector.json":({name:$connector,config:{"table.include.list":$table,
      "transforms.outbox.table.fields.additional.placement":"headers_json:header:headers_json,payload_type:header:eventType"}}|tojson)}}' \
    >"$config_file"
done
jq -n '{kind:"StatefulSet",metadata:{name:"postgres"},spec:{replicas:1},status:{readyReplicas:1}}' >"$postgres"
for job in "$topic_provisioning" "$account_flyway" "$risk_flyway" "$persistence_flyway" \
    "$market_data_projection_flyway" "$query_flyway" "$quickfix_gateway_flyway"; do
  jq -n '{kind:"Job",status:{conditions:[{type:"Complete",status:"True"}]}}' >"$job"
done
for topic in "$connect_configs_topic" "$connect_offsets_topic" "$connect_status_topic"; do
  printf '%s\n' 'ReplicationFactor: 3' 'min.insync.replicas=2' >"$topic"
done
mkdir -p "$control_plane_dir"
printf '%s\n' 'readyz check passed' >"$control_plane_dir/readyz.txt"
jq -n '[{name:"etcd-control-plane",phase:"Running",ready:true,restart_count:0},
  {name:"kube-controller-manager-control-plane",phase:"Running",ready:true,restart_count:0},
  {name:"kube-scheduler-control-plane",phase:"Running",ready:true,restart_count:0}]' \
  >"$control_plane_dir/before.json"
cp "$control_plane_dir/before.json" "$control_plane_dir/after.json"
jq -n '{items:[]}' >"$control_plane_dir/events.json"

report_is_valid() {
  (cd "$fixture_dir" && connect_worker_loss_report_is_valid report.json)
}

report_is_passed() {
  local name="${1:-report.json}"
  (cd "$fixture_dir" && connect_worker_loss_report_is_passed "$name")
}

jq '.tasks[0].worker_id = "10.244.0.11:8083"' "$status_after" >"$fixture_dir/status-same-worker.json"
if connect_worker_loss_assert_reassignment \
    "$status_before" "$fixture_dir/status-same-worker.json" "$target_before" "$target_after"; then
  fail 'unchanged task owner was accepted'
fi
jq '.tasks[0].id = 1' "$status_after" >"$fixture_dir/status-changed-task.json"
if connect_worker_loss_assert_reassignment \
    "$status_before" "$fixture_dir/status-changed-task.json" "$target_before" "$target_after"; then
  fail 'changed task identity was accepted'
fi
jq '.items[0].metadata.labels = {}' "$pods_before" >"$fixture_dir/pods-missing-label.json"
if connect_worker_loss_pods_are_valid "$fixture_dir/pods-missing-label.json"; then
  fail 'unlabelled Connect Pod was accepted'
fi

jq -n \
  --argjson schema_version "$CONNECT_WORKER_LOSS_REPORT_SCHEMA_VERSION" \
  --argjson before "$(cat "$target_before")" --argjson after "$(cat "$target_after")" \
  '{schema_version:$schema_version,profile:"connect-worker-loss",status:"PASSED",
    cluster:"simplematch-live",context:"kind-simplematch-live",
    namespace:"simplematch-cert-run",namespace_run_id:"run-1",
    run_id:"connect-worker-loss-run-1",fault_mode:"pod-delete",deadline_seconds:300,
    prerequisites:{connect_workers:2,ready_workers_before:2,ready_workers_after:2,
      internal_topics_rf3:true,pdb_min_available_1:true,connect_has_no_pvc:true,
      service_owned_connectors:true,flyway_and_topic_prerequisites:true},
    task_reassignment:{connector:"account-service-outbox",task_id:0,before:$before,after:$after,
      task_id_unchanged:true,worker_id_changed:true,pod_uid_changed:true,node_changed:true},
    publication:{baseline_captured:true,post_transition_probe:true,exact_kafka_record:true,
      transition_after_reassignment:true,transition_file:"account-transition.json",
      event_id:"00000000-0000-7000-8000-000000000001",
      baseline_file:"account-outbox-baseline.json",probe_file:"account-outbox-probe.json",
      kafka_baseline_file:"account-kafka-baseline.tsv",
      publication_evidence_file:"account-publication.json"},
    evidence:{status_before_file:"status-before.json",status_after_file:"status-after.json",
      pods_before_file:"pods-before.json",pods_after_file:"pods-after.json",
      target_before_file:"target-before.json",target_after_file:"target-after.json",
      status_pre_delete_file:"status-pre-delete.json",pods_pre_delete_file:"pods-pre-delete.json",
      target_pre_delete_file:"target-pre-delete.json",
      pod_pre_delete_file:"pod-pre-delete.json",pod_patch_file:"pod-patch.json",
      pod_delete_precondition_file:"pod-delete-precondition.json",
      worker_loss_file:"worker-loss.json",provenance_file:"provenance.json",
      transition_file:"account-transition.json",
      baseline_file:"account-outbox-baseline.json",probe_file:"account-outbox-probe.json",
      kafka_baseline_file:"account-kafka-baseline.tsv",
      publication_evidence_file:"account-publication.json",
      nodes_file:"nodes.json",connect_deployment_file:"connect-deployment.json",
      control_plane_readyz_file:"control-plane/readyz.txt",
      control_plane_before_file:"control-plane/before.json",
      control_plane_after_file:"control-plane/after.json",
      control_plane_events_file:"control-plane/events.json",
      connect_pdb_file:"connect-pdb.json",
      account_connector_file:"account-service-outbox-configmap.json",
      risk_connector_file:"risk-service-outbox-configmap.json",postgres_file:"postgres.json",
      topic_provisioning_file:"topic-provisioning.json",account_flyway_file:"account-flyway.json",
      risk_flyway_file:"risk-flyway.json",persistence_flyway_file:"persistence-flyway.json",
      market_data_projection_flyway_file:"market-data-projection-flyway.json",
      query_flyway_file:"query-flyway.json",quickfix_gateway_flyway_file:"quickfix-gateway-flyway.json",
      connect_configs_topic_file:"connect-configs.txt",connect_offsets_topic_file:"connect-offsets.txt",
      connect_status_topic_file:"connect-status.txt"},failure_reason:null,
    claim_boundary:["focused local worker-loss"]}' >"$report"
report_is_valid || fail 'valid report envelope was rejected'
report_is_passed || fail 'valid report did not pass'
jq '.publication.exact_kafka_record = false' "$report" >"$fixture_dir/report-no-publication.json"
if report_is_passed report-no-publication.json; then
  fail 'report without exact Kafka publication was accepted'
fi
jq 'del(.evidence)' "$report" >"$fixture_dir/report-without-evidence.json"
if report_is_passed report-without-evidence.json; then
  fail 'report without linked evidence files was accepted'
fi
jq 'del(.task_reassignment.before)' "$report" >"$fixture_dir/report-without-owner.json"
if report_is_passed report-without-owner.json; then
  fail 'report without before owner evidence was accepted'
fi
jq '.evidence.publication_evidence_file = "account-kafka-baseline.tsv"' "$report" \
  >"$fixture_dir/report-with-forged-publication.json"
if report_is_passed report-with-forged-publication.json; then
  fail 'report with forged Kafka publication evidence was accepted'
fi
jq 'del(.evidence.connect_deployment_file)' "$report" >"$fixture_dir/report-without-prerequisite.json"
if report_is_passed report-without-prerequisite.json; then
  fail 'report without linked prerequisite evidence was accepted'
fi

runtime_script="$script_dir/run-local-connect-worker-loss.sh"
grep -Fq 'connect_worker_loss_target_identity' "$runtime_script" ||
  fail 'runtime does not resolve the task-owning Pod through the Module'
grep -Fq 'connect_worker_loss_assert_reassignment' "$runtime_script" ||
  fail 'runtime does not require task reassignment evidence'
grep -Fq 'cdc_capture_outbox_baseline' "$runtime_script" ||
  fail 'runtime does not capture an outbox baseline through cdc-verifier'
grep -Fq 'cdc_read_outbox_probe' "$runtime_script" ||
  fail 'runtime does not read the post-transition event through cdc-verifier'
grep -Fq 'cdc_assert_probe_publication' "$runtime_script" ||
  fail 'runtime does not verify exact Kafka publication through cdc-verifier'
grep -Fq 'recheck_target_before_delete' "$runtime_script" ||
  fail 'runtime does not re-check task owner identity before deletion'
grep -Fq 'uid_precondition_test' "$runtime_script" ||
  fail 'runtime does not record its UID precondition evidence'
grep -Fq 'validate_retained_provenance' "$runtime_script" ||
  fail 'runtime does not validate source-aligned retained provenance'
grep -Fq 'simplematch_kind_validate_control_plane_stability' "$runtime_script" ||
  fail 'runtime does not gate fault injection on control-plane stability'
grep -Fq -- '--retained-evidence-dir' "$runtime_script" ||
  fail 'runtime does not expose the retained evidence directory boundary'
grep -Fq "account-service-outbox) table='account_service.outbox'" "$runtime_script" ||
  fail 'runtime does not preserve the Account connector outbox table identity'
grep -Fq "risk-service-outbox) table='risk_service.outbox'" "$runtime_script" ||
  fail 'runtime does not preserve the Risk connector outbox table identity'
grep -Fq '.data["connector.json"]' "$runtime_script" ||
  fail 'runtime does not read the deployed connector.json ConfigMap key'
if grep -Fq '.data.connector | fromjson' "$runtime_script"; then
  fail 'runtime reads a non-existent connector ConfigMap key'
fi
derived_table_expression="\${connector%-outbox}.outbox"
if grep -Fq "$derived_table_expression" "$runtime_script"; then
  fail 'runtime derives SQL table names from connector names'
fi
if rg -n 'SELECT[[:space:]]+event_id|ORDER BY[[:space:]]+.*event_id' "$runtime_script" >/dev/null; then
  fail 'runtime duplicates outbox event-selection SQL'
fi

dry_run_output="$(bash "$runtime_script" \
  --namespace simplematch-cert-run --namespace-run-id run-1 --dry-run)"
grep -Fq 'delete exactly that Connect Pod' <<<"$dry_run_output" ||
  fail 'runtime dry-run does not describe the fail-closed fault plan'
if bash "$runtime_script" --namespace simplematch-cert-run --namespace-run-id run-1 \
    --evidence-dir /tmp/simplematch-connect-worker-loss-absolute --dry-run >/dev/null 2>&1; then
  fail 'runtime accepted an absolute evidence path'
fi

printf '%s\n' 'Local Kafka Connect worker-loss contract passed.'

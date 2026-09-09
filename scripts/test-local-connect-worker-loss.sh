#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/cdc-verifier.sh
source "$script_dir/lib/cdc-verifier.sh"
# shellcheck source=scripts/lib/local-resilience.sh
source "$script_dir/lib/local-resilience.sh"
# shellcheck source=scripts/lib/connect-worker-loss.sh
source "$script_dir/lib/connect-worker-loss.sh"
# shellcheck source=scripts/lib/connect-worker-loss-scenario.sh
source "$script_dir/lib/connect-worker-loss-scenario.sh"
# shellcheck source=scripts/lib/connect-worker-loss-cli.sh
source "$script_dir/lib/connect-worker-loss-cli.sh"

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
delete_observation="$fixture_dir/target-delete-observation.json"
worker_loss="$fixture_dir/worker-loss.json"
provenance="$fixture_dir/provenance.json"
verifier_contract="$fixture_dir/verifier-contract.log"
verifier_contract_script="$script_dir/test-cdc-observer-fixture-contract.sh"
verifier_contract_copy="$fixture_dir/verifier-contract.sh"
verifier_observer_script="$fixture_dir/verifier-observer.sh"
baseline="$fixture_dir/account-outbox-baseline.json"
probe="$fixture_dir/account-outbox-probe.json"
kafka_baseline="$fixture_dir/account-kafka-baseline.tsv"
publication_evidence="$fixture_dir/account-publication.json"
transition="$fixture_dir/account-transition.json"
image_cache_preflight="$fixture_dir/image-cache-preflight.json"
nodes="$fixture_dir/nodes.json"
deployment="$fixture_dir/connect-deployment.json"
connect_config="$fixture_dir/connect-config.json"
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
retained_authority="$fixture_dir/retained"
mkdir -p "$retained_authority"
printf '%s\n' \
  'run_id=run-1' \
  'namespace=simplematch-cert-run' \
  'cluster=simplematch-live' \
  'cdc_runtime_signature=0000000000000000000000000000000000000000000000000000000000000000' \
  'cdc_verifier_signature=1111111111111111111111111111111111111111111111111111111111111111' \
  >"$retained_authority/run-context"
printf '%s\n' '0000000000000000000000000000000000000000' >"$retained_authority/source-revision"
printf '%s\n' 'sha256:2222222222222222222222222222222222222222222222222222222222222222' \
  >"$retained_authority/verifier-image-identity"
printf '%s\n' 'simplematch-cert-run' >"$retained_authority/retained-namespace"

jq -n '{name:"account-service-outbox",connector:{state:"RUNNING",worker_id:"10.244.0.11:8083"},tasks:[{id:0,state:"RUNNING",worker_id:"10.244.0.11:8083"}]}' >"$status_before"
jq -n '{name:"account-service-outbox",connector:{state:"RUNNING",worker_id:"10.244.0.33:8083"},tasks:[{id:0,state:"RUNNING",worker_id:"10.244.0.33:8083"}]}' >"$status_after"
jq -n '{name:"account-service-outbox",connector:{state:"RUNNING",worker_id:"10.244.0.11:8083"},tasks:[]}' >"$status_running_only"

cat >"$pods_before" <<'EOF_PODS'
{"items":[
  {"metadata":{"name":"kafka-connect-a","uid":"uid-a","labels":{"app.kubernetes.io/name":"kafka-connect","app.kubernetes.io/component":"connector"}},"spec":{"nodeName":"simplematch-live-worker","volumes":[{"name":"runtime-tmp","emptyDir":{}}]},"status":{"podIP":"10.244.0.11","conditions":[{"type":"Ready","status":"True"}]}},
  {"metadata":{"name":"kafka-connect-b","uid":"uid-b","labels":{"app.kubernetes.io/name":"kafka-connect","app.kubernetes.io/component":"connector"}},"spec":{"nodeName":"simplematch-live-worker2","volumes":[{"name":"runtime-tmp","emptyDir":{}}]},"status":{"podIP":"10.244.0.22","conditions":[{"type":"Ready","status":"True"}]}}
]}
EOF_PODS
cp -- "$pods_before" "$fixture_dir/pods-before-valid.json"
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
connect_worker_loss_parse_args \
  --namespace simplematch-cert-run --namespace-run-id run-1 --deadline-seconds 7 ||
  fail 'valid CLI request was rejected'
[[ "${CONNECT_WORKER_LOSS_REQUEST[deadline_seconds]}" == 7 ]] ||
  fail 'CLI request did not retain the requested deadline'
if connect_worker_loss_parse_args --namespace simplematch-cert-run \
    --namespace-run-id run-1 --deadline-seconds 901; then
  fail 'CLI request accepted an over-budget deadline'
fi
[[ "${#CONNECT_WORKER_LOSS_REQUEST[@]}" == 0 ]] ||
  fail 'invalid CLI request left stale parsed options in the public request'
connect_worker_loss_configure_adapters fake-kubectl fake-kind fake-curl ||
  fail 'adapter bundle rejected injected binaries'
[[ "$SIMPLEMATCH_KIND_KUBECTL_BIN" == fake-kubectl &&
  "$SIMPLEMATCH_KIND_BIN" == fake-kind &&
  "$CONNECT_REST_TUNNEL_KUBECTL_BIN" == fake-kubectl &&
  "$CONNECT_REST_TUNNEL_CURL_BIN" == fake-curl ]] ||
  fail 'injected adapter binaries were not shared across helper modules'
connect_worker_loss_configure_adapters kubectl kind curl ||
  fail 'adapter bundle could not be restored'
connect_worker_loss_delete_output_is_exact \
  kafka-connect-a 'pod/kafka-connect-a deletion requested' ||
  fail 'current kubectl deletion output was rejected'
connect_worker_loss_delete_output_is_exact \
  kafka-connect-a 'pod "kafka-connect-a" deleted from namespace namespace' ||
  fail 'legacy kubectl deletion output was rejected'
if connect_worker_loss_delete_output_is_exact \
    kafka-connect-a $'pod/kafka-connect-a deletion requested\npod/kafka-connect-b deleted'; then
  fail 'multi-Pod deletion output was accepted'
fi

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
  delete_output:"pod/kafka-connect-a deletion requested",
  delete_requested:true,uid_precondition_test:true,pre_delete_recheck:true,
  selector_target_count:1,delete_output_contains_target:true,target_uid_absent:true,
  recovery_deadline_started_at_unix_ms:1,requested_at_unix_ms:2,
  requested_at:"2026-09-03T00:00:00Z"}' >"$worker_loss"
jq -n '{schema_version:1,target_pod:"kafka-connect-a",target_pod_uid:"uid-a",
  outcome:"not-found",replacement_pod_uid:null,target_uid_absent:true,
  observed_at_utc:"2026-09-03T00:00:00Z"}' >"$delete_observation"
jq -n --arg contract_path "$verifier_contract_script" \
  --arg contract_sha256 "$(sha256sum "$verifier_contract_script" | awk '{print $1}')" \
  --arg observer_path "$script_dir/run-risk-cdc-delivery-observer-check.sh" \
  --arg observer_sha256 "$(sha256sum "$script_dir/run-risk-cdc-delivery-observer-check.sh" | awk '{print $1}')" \
  '{status:"PASS",retained_evidence_dir:"retained",namespace:"simplematch-cert-run",namespace_run_id:"run-1",
  current_commit:"0000000000000000000000000000000000000000",
  cdc_runtime_signature:"0000000000000000000000000000000000000000000000000000000000000000",
  retained_cdc_runtime_signature:"0000000000000000000000000000000000000000000000000000000000000000",
  cdc_verifier_signature:"1111111111111111111111111111111111111111111111111111111111111111",
  retained_cdc_verifier_signature:"1111111111111111111111111111111111111111111111111111111111111111",
  verifier_signature_changed:false,runtime_reused:true,
  verifier_image_identity:"sha256:2222222222222222222222222222222222222222222222222222222222222222",
  verifier_observer_path:$observer_path,verifier_observer_sha256:$observer_sha256,
  verifier_observer_evidence_file:"verifier-observer.sh",
  verifier_contract_path:$contract_path,verifier_contract_sha256:$contract_sha256,
  verifier_contract_evidence_file:"verifier-contract.sh"}' >"$provenance"
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
printf '%s\n' '0	0' '1	2' >"$kafka_baseline"
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
  --arg key_sha "$(printf '%s\n' 'account-1' | sha256sum | awk '{print $1}')" \
  '{schema_version:2,status:"PASS",topic:"account.lifecycle",event_id:$event_id,
    partition:1,offset:2,expected_message_key:"account-1",
    expected_timestamp_unix_ms:2,expected_headers_json_sha256:$headers_sha,
    expected_event_type:"simplematch.account.v2.AccountLifecycleEvent",
    expected_payload_sha256:$payload_sha,
    observed:{partition:1,offset:2,timestamp_unix_ms:2,key_sha256:$key_sha,
      payload_sha256:$payload_sha,headers_sha256:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      headers_json_sha256:$headers_sha,event_id:$event_id,
      event_type:"simplematch.account.v2.AccountLifecycleEvent",header_count:7},
    verification:{headers_exact:true,key_exact:true,timestamp_exact:true,payload_exact:true}}' \
  >"$publication_evidence"
jq -n '{items:[
  {metadata:{name:"simplematch-live-worker",labels:{"simplematch.io/node-pool":"local-resilience","simplematch.io/worker-slot":"0"}},status:{conditions:[{type:"Ready",status:"True"}]}},
  {metadata:{name:"simplematch-live-worker2",labels:{"simplematch.io/node-pool":"local-resilience","simplematch.io/worker-slot":"1"}},status:{conditions:[{type:"Ready",status:"True"}]}}
]}' >"$nodes"
jq -n '{metadata:{name:"kafka-connect"},spec:{replicas:2,template:{spec:{nodeSelector:{"simplematch.io/node-pool":"local-resilience"},
  topologySpreadConstraints:[{maxSkew:1,topologyKey:"simplematch.io/worker-slot",whenUnsatisfiable:"DoNotSchedule",
    labelSelector:{matchLabels:{"app.kubernetes.io/name":"kafka-connect","app.kubernetes.io/component":"connector"}}}],
  tolerations:[{key:"simplematch.io/portable-workload",operator:"Exists",effect:"NoExecute",tolerationSeconds:30},
    {key:"node.kubernetes.io/not-ready",operator:"Exists",effect:"NoExecute",tolerationSeconds:30},
    {key:"node.kubernetes.io/unreachable",operator:"Exists",effect:"NoExecute",tolerationSeconds:30}],
  volumes:[{name:"tmp",emptyDir:{}}],containers:[{name:"kafka-connect",image:"quay.io/debezium/connect:3.6.0.Final",
    env:[{name:"GROUP_ID",value:"simplematch-connect-local"},{name:"CONFIG_STORAGE_TOPIC",value:"simplematch-connect-configs"},
      {name:"OFFSET_STORAGE_TOPIC",value:"simplematch-connect-offsets"},{name:"STATUS_STORAGE_TOPIC",value:"simplematch-connect-status"},
      {name:"CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR",value:"3"},{name:"CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR",value:"3"},
      {name:"CONNECT_STATUS_STORAGE_REPLICATION_FACTOR",value:"3"},{name:"CONNECT_CONFIG_PROVIDERS",value:"envvarprovider"},
      {name:"CONNECT_CONFIG_PROVIDERS_ENVVARPROVIDER_CLASS",value:"org.apache.kafka.common.config.provider.EnvVarConfigProvider"}]}]}}}}' >"$deployment"
jq -n '{metadata:{name:"simplematch-kafka-connect-config"},data:{bootstrap_servers:"kafka:9092",
  postgres_hostname:"postgres",postgres_port:"5432",postgres_dbname:"simplematch",postgres_sslmode:"disable",
  postgres_sslrootcert:"/dev/null"}}' >"$connect_config"
jq -n '{metadata:{name:"kafka-connect"},spec:{minAvailable:1,selector:{matchLabels:{"app.kubernetes.io/name":"kafka-connect","app.kubernetes.io/component":"connector"}}}}' >"$pdb"
for connector in account-service-outbox risk-service-outbox; do
  table='account_service.outbox'
  config_file="$account_config"
  if [[ "$connector" == risk-service-outbox ]]; then
    table='risk_service.outbox'
    config_file="$risk_config"
  fi
  jq -n --arg connector "$connector" --arg table "$table" \
    '{metadata:{name:($connector + "-connector")},data:{"connector.json":({name:$connector,config:{"table.include.list":$table,
      "transforms.outbox.table.fields.additional.placement":"headers_json:header:headers_json,payload_type:header:eventType"}}|tojson)}}' \
    >"$config_file"
done
jq -n '{kind:"StatefulSet",metadata:{name:"postgres"},spec:{replicas:1},status:{readyReplicas:1}}' >"$postgres"
mkdir -p "$fixture_dir/prerequisites"
cp -- "$postgres" "$fixture_dir/prerequisites/postgres.json"
# This is a deterministic contract-test identity, not a production image digest.
fixture_image_identity='sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
jq -n --arg image 'quay.io/debezium/connect:3.6.0.Final' --arg identity "$fixture_image_identity" \
  '{schema_version:1,status:"PASS",context:"kind-simplematch-live",image_reference:$image,
    image_identity:$identity,identity_source:"first-eligible-node",budget_seconds:60,
    started_at_utc:"2026-09-03T00:00:00Z",completed_at_utc:"2026-09-03T00:00:01Z",
    nodes:[
      {node:"simplematch-live-worker",status:"PASS",inspect_status:"PASS",
        execution_probe_status:"PASS",identity:$identity,failure_reason:null},
      {node:"simplematch-live-worker2",status:"PASS",inspect_status:"PASS",
        execution_probe_status:"PASS",identity:$identity,failure_reason:null}],
    failure_reason:null}' >"$image_cache_preflight"
cp -- "$image_cache_preflight" "$fixture_dir/image-cache-valid.json"
for job_spec in \
    "kafka-topic-provisioning:$topic_provisioning" \
    "account-service-flyway:$account_flyway" \
    "risk-service-flyway:$risk_flyway" \
    "persistence-flyway:$persistence_flyway" \
    "market-data-projection-flyway:$market_data_projection_flyway" \
    "query-service-flyway:$query_flyway" \
    "quickfix-gateway-flyway:$quickfix_gateway_flyway"; do
  job_name="${job_spec%%:*}"
  job_file="${job_spec#*:}"
  jq -n --arg name "$job_name" \
    '{kind:"Job",metadata:{name:$name},status:{conditions:[{type:"Complete",status:"True"}]}}' \
    >"$job_file"
done
printf '%s\n' \
  'Topic: simplematch-connect-configs TopicId: fixture-configs PartitionCount: 3 ReplicationFactor: 3 Configs: cleanup.policy=compact,min.insync.replicas=2' \
  >"$connect_configs_topic"
printf '%s\n' \
  'Topic: simplematch-connect-offsets TopicId: fixture-offsets PartitionCount: 3 ReplicationFactor: 3 Configs: cleanup.policy=compact,min.insync.replicas=2' \
  >"$connect_offsets_topic"
printf '%s\n' \
  'Topic: simplematch-connect-status TopicId: fixture-status PartitionCount: 3 ReplicationFactor: 3 Configs: cleanup.policy=compact,min.insync.replicas=2' \
  >"$connect_status_topic"
mkdir -p "$control_plane_dir"
printf '%s\n' 'readyz check passed' >"$control_plane_dir/readyz.txt"
printf '%s\n' 'CDC observer fixture header contract is valid.' >"$verifier_contract"
cp -- "$verifier_contract_script" "$verifier_contract_copy"
cp -- "$script_dir/run-risk-cdc-delivery-observer-check.sh" "$verifier_observer_script"
jq -n '[{name:"etcd-simplematch-live-control-plane",phase:"Running",ready:true,restart_count:0},
  {name:"kube-controller-manager-simplematch-live-control-plane",phase:"Running",ready:true,restart_count:0},
  {name:"kube-scheduler-simplematch-live-control-plane",phase:"Running",ready:true,restart_count:0}]' \
  >"$control_plane_dir/before.json"
cp "$control_plane_dir/before.json" "$control_plane_dir/after.json"
jq -n '{items:[]}' >"$control_plane_dir/events.json"

typed_prerequisites="$fixture_dir/typed-prerequisites"
mkdir -p "$typed_prerequisites/control-plane" "$typed_prerequisites/prerequisites"
cp -- "$nodes" "$typed_prerequisites/nodes.json"
cp -- "$control_plane_dir/readyz.txt" "$typed_prerequisites/control-plane/readyz.txt"
cp -- "$control_plane_dir/before.json" "$typed_prerequisites/control-plane/before.json"
cp -- "$control_plane_dir/after.json" "$typed_prerequisites/control-plane/after.json"
cp -- "$control_plane_dir/events.json" "$typed_prerequisites/control-plane/events.json"
cp -- "$deployment" "$typed_prerequisites/connect-deployment.json"
cp -- "$pdb" "$typed_prerequisites/connect-pdb.json"
cp -- "$connect_config" "$typed_prerequisites/connect-config.json"
cp -- "$image_cache_preflight" "$typed_prerequisites/image-cache-preflight.json"
cp -- "$pods_before" "$typed_prerequisites/connect-pods-before.json"
cp -- "$account_config" "$typed_prerequisites/account-service-outbox-configmap.json"
cp -- "$risk_config" "$typed_prerequisites/risk-service-outbox-configmap.json"
cp -- "$postgres" "$typed_prerequisites/prerequisites/postgres.json"
cp -- "$topic_provisioning" \
  "$typed_prerequisites/prerequisites/kafka-topic-provisioning.json"
cp -- "$account_flyway" \
  "$typed_prerequisites/prerequisites/account-service-flyway.json"
cp -- "$risk_flyway" \
  "$typed_prerequisites/prerequisites/risk-service-flyway.json"
cp -- "$persistence_flyway" \
  "$typed_prerequisites/prerequisites/persistence-flyway.json"
cp -- "$market_data_projection_flyway" \
  "$typed_prerequisites/prerequisites/market-data-projection-flyway.json"
cp -- "$query_flyway" \
  "$typed_prerequisites/prerequisites/query-service-flyway.json"
cp -- "$quickfix_gateway_flyway" \
  "$typed_prerequisites/prerequisites/quickfix-gateway-flyway.json"
cp -- "$connect_configs_topic" "$typed_prerequisites/prerequisites/simplematch-connect-configs.txt"
cp -- "$connect_offsets_topic" "$typed_prerequisites/prerequisites/simplematch-connect-offsets.txt"
cp -- "$connect_status_topic" "$typed_prerequisites/prerequisites/simplematch-connect-status.txt"
(cd "$fixture_dir" && ruby "$script_dir/lib/connect-worker-loss-evidence.rb" \
  prerequisites typed-prerequisites) || fail 'typed prerequisite mode rejected valid evidence'

report_is_valid() {
  local name="${1:-report.json}"
  (cd "$fixture_dir" && connect_worker_loss_report_is_valid "$name")
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
  --argjson schema_version "$(connect_worker_loss_report_schema_version)" \
  --argjson before "$(cat "$target_before")" --argjson after "$(cat "$target_after")" \
  '{schema_version:$schema_version,profile:"connect-worker-loss",status:"PASSED",
    cluster:"simplematch-live",context:"kind-simplematch-live",
    namespace:"simplematch-cert-run",namespace_run_id:"run-1",
    run_id:"connect-worker-loss-run-1",fault_mode:"pod-delete",deadline_seconds:300,
    recovery_deadline_started_at_unix_ms:1,
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
      nodes_file:"nodes.json",connect_deployment_file:"connect-deployment.json",
      connect_config_file:"connect-config.json",
      control_plane_readyz_file:"control-plane/readyz.txt",
      control_plane_before_file:"control-plane/before.json",
      control_plane_after_file:"control-plane/after.json",
      control_plane_events_file:"control-plane/events.json",
      connect_pdb_file:"connect-pdb.json",
      account_connector_file:"account-service-outbox-configmap.json",
      risk_connector_file:"risk-service-outbox-configmap.json",postgres_file:"prerequisites/postgres.json",
      topic_provisioning_file:"topic-provisioning.json",account_flyway_file:"account-flyway.json",
      risk_flyway_file:"risk-flyway.json",persistence_flyway_file:"persistence-flyway.json",
      market_data_projection_flyway_file:"market-data-projection-flyway.json",
      query_flyway_file:"query-flyway.json",quickfix_gateway_flyway_file:"quickfix-gateway-flyway.json",
      connect_configs_topic_file:"connect-configs.txt",connect_offsets_topic_file:"connect-offsets.txt",
      connect_status_topic_file:"connect-status.txt"},failure_reason:null,
    claim_boundary:["focused local worker-loss"]}' >"$report"
report_is_valid || fail 'valid report envelope was rejected'
report_is_passed || fail 'valid report did not pass'

jq '.evidence.risk_flyway_file = "topic-provisioning.json"' "$report" \
  >"$fixture_dir/report-aliased-job.json"
if report_is_passed report-aliased-job.json; then
  fail 'one completed Job was accepted as two distinct prerequisites'
fi
jq '.evidence.control_plane_after_file = "control-plane/before.json"' "$report" \
  >"$fixture_dir/report-aliased-control-plane.json"
if report_is_passed report-aliased-control-plane.json; then
  fail 'one control-plane snapshot was accepted as two snapshots'
fi
ln -s . "$fixture_dir/evidence-parent-link"
jq '.evidence.status_before_file = "evidence-parent-link/status-before.json"' "$report" \
  >"$fixture_dir/report-symlink-evidence.json"
if report_is_passed report-symlink-evidence.json; then
  fail 'evidence path through a symlinked parent was accepted'
fi

jq '.items[0].status.conditions[0].status = "False"' "$pods_before" \
  >"$fixture_dir/pods-before-not-ready.json"
jq '.evidence.pods_before_file = "pods-before-not-ready.json"' "$report" \
  >"$fixture_dir/report-target-not-ready.json"
if report_is_passed report-target-not-ready.json; then
  fail 'non-Ready target Pod was accepted by the typed verifier'
fi

jq '.failure_reason = "unexpected failure"' "$image_cache_preflight" \
  >"$fixture_dir/image-cache-failure-reason.json"
jq '.evidence.image_cache_preflight_file = "image-cache-failure-reason.json"' "$report" \
  >"$fixture_dir/report-image-cache-failure.json"
if report_is_passed report-image-cache-failure.json; then
  fail 'image-cache PASS with a failure reason was accepted'
fi

printf '%s\n' \
  'Topic: wrong-topic TopicId: fixture-configs PartitionCount: 3 ReplicationFactor: 30 Configs: cleanup.policy=compact,min.insync.replicas=20' \
  >"$fixture_dir/connect-configs-invalid.txt"
jq '.evidence.connect_configs_topic_file = "connect-configs-invalid.txt"' "$report" \
  >"$fixture_dir/report-topic-invalid.json"
if report_is_passed report-topic-invalid.json; then
  fail 'wrong topic or loose replication settings were accepted'
fi

jq '.items = [
  (.items[1] | .metadata.name = "kafka-connect-a" |
    .metadata.uid = "uid-replacement" | .spec.nodeName = "simplematch-live-worker3" |
    .status.podIP = "10.244.0.44"), .items[0]
]' "$pods_after" >"$fixture_dir/pods-after-replacement.json"
jq '.connector.worker_id = "10.244.0.44:8083" |
  .tasks[0].worker_id = "10.244.0.44:8083"' "$status_after" \
  >"$fixture_dir/status-after-replacement.json"
jq '.worker_id = "10.244.0.44:8083" | .worker_host = "10.244.0.44" |
  .pod = "kafka-connect-a" | .pod_uid = "uid-replacement" |
  .node = "simplematch-live-worker3" | .pod_ip = "10.244.0.44" |
  .worker_slot = "2"' "$target_after" >"$fixture_dir/target-after-replacement.json"
jq -n '{schema_version:1,target_pod:"kafka-connect-a",target_pod_uid:"uid-a",
  outcome:"replacement-pod",replacement_pod_uid:"uid-replacement",target_uid_absent:true,
  observed_at_utc:"2026-09-03T00:00:00Z"}' >"$fixture_dir/replacement-observation.json"
jq --slurpfile after "$fixture_dir/target-after-replacement.json" \
  '.task_reassignment.after = $after[0] |
   .evidence.status_after_file = "status-after-replacement.json" |
   .evidence.pods_after_file = "pods-after-replacement.json" |
   .evidence.target_after_file = "target-after-replacement.json" |
   .evidence.target_delete_observation_file = "replacement-observation.json"' \
  "$report" >"$fixture_dir/report-replacement.json"
report_is_passed report-replacement.json ||
  fail 'replacement Pod observation was not linked to the after snapshot'
jq '.items[0].spec.nodeName = "simplematch-live-worker"' \
  "$fixture_dir/pods-after-replacement.json" >"$fixture_dir/pods-after-same-node.json"
jq '.node = "simplematch-live-worker" | .worker_slot = "0"' \
  "$fixture_dir/target-after-replacement.json" >"$fixture_dir/target-after-same-node.json"
jq --slurpfile target "$fixture_dir/target-after-same-node.json" \
  '.task_reassignment.after = $target[0] |
   .task_reassignment.node_changed = false |
   .evidence.pods_after_file = "pods-after-same-node.json" |
   .evidence.target_after_file = "target-after-same-node.json"' \
  "$fixture_dir/report-replacement.json" >"$fixture_dir/report-replacement-same-node.json"
report_is_passed report-replacement-same-node.json ||
  fail 'valid same-node Pod replacement was rejected'
jq '.replacement_pod_uid = "uid-other"' "$fixture_dir/replacement-observation.json" \
  >"$fixture_dir/replacement-observation-forged.json"
jq '.evidence.target_delete_observation_file = "replacement-observation-forged.json"' \
  "$fixture_dir/report-replacement.json" >"$fixture_dir/report-replacement-forged.json"
if report_is_passed report-replacement-forged.json; then
  fail 'replacement Pod observation was accepted without after-snapshot linkage'
fi
jq '.data["connector.json"] = "not-json"' "$account_config" >"$account_config.tmp" &&
  mv "$account_config.tmp" "$account_config"
if report_is_passed; then
  fail 'malformed connector prerequisite escaped as an uncontrolled error'
fi
# Restore the fixture from its deterministic source after the negative check.
jq -n '{metadata:{name:"account-service-outbox-connector"},data:{"connector.json":({name:"account-service-outbox",config:{"table.include.list":"account_service.outbox","transforms.outbox.table.fields.additional.placement":"headers_json:header:headers_json,payload_type:header:eventType"}}|tojson)}}' >"$account_config"
jq '.diagnostics = {attempts: 1}' "$report" >"$fixture_dir/report-with-diagnostics.json"
report_is_passed report-with-diagnostics.json ||
  fail 'additive diagnostic metadata broke the public evidence contract'
jq '.task_reassignment.task_id = "0"' "$report" >"$fixture_dir/report-string-task-id.json"
if report_is_passed report-string-task-id.json; then
  fail 'string task identity bypassed the typed evidence contract'
fi
jq '.deadline_seconds = 901' "$report" >"$fixture_dir/report-over-budget.json"
if report_is_valid report-over-budget.json; then
  fail 'over-budget worker-loss report was accepted'
fi
jq '.schema_version = 1' "$report" >"$fixture_dir/report-legacy-schema.json"
if report_is_valid report-legacy-schema.json; then
  fail 'legacy worker-loss report schema was accepted'
fi
printf '%s\n' 'tampered' >>"$verifier_contract_copy"
if report_is_passed; then
  fail 'tampered retained verifier contract copy was accepted'
fi
cp -- "$verifier_contract_script" "$verifier_contract_copy"
jq '.cdc_verifier_signature =
  "2222222222222222222222222222222222222222222222222222222222222222" |
  .verifier_signature_changed = true' "$provenance" >"$fixture_dir/provenance-verifier-drift.json"
mv "$fixture_dir/provenance-verifier-drift.json" "$provenance"
if ! report_is_passed; then
  fail 'verifier-only provenance drift was rejected'
fi
jq '.verifier_signature_changed = false' "$provenance" >"$fixture_dir/provenance-forged-drift.json"
mv "$fixture_dir/provenance-forged-drift.json" "$provenance"
if report_is_passed; then
  fail 'forged verifier drift flag was accepted'
fi
jq '.verifier_signature_changed = true' "$provenance" >"$fixture_dir/provenance-verifier-drift-restored.json"
mv "$fixture_dir/provenance-verifier-drift-restored.json" "$provenance"
jq '.cdc_runtime_signature =
  "3333333333333333333333333333333333333333333333333333333333333333"' \
  "$provenance" >"$fixture_dir/provenance-runtime-drift.json"
mv "$fixture_dir/provenance-runtime-drift.json" "$provenance"
if report_is_passed; then
  fail 'runtime provenance drift was accepted'
fi
jq '.cdc_runtime_signature =
  "0000000000000000000000000000000000000000000000000000000000000000"' \
  "$provenance" >"$fixture_dir/provenance-restored.json"
mv "$fixture_dir/provenance-restored.json" "$provenance"
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
jq 'del(.evidence.target_delete_observation_file)' "$report" \
  >"$fixture_dir/report-without-delete-observation.json"
if report_is_passed report-without-delete-observation.json; then
  fail 'report without linked Pod deletion observation was accepted'
fi
jq '.offset = 1 | .observed.offset = 1' "$publication_evidence" \
  >"$fixture_dir/account-publication-before-baseline.json"
jq '.evidence.publication_evidence_file = "account-publication-before-baseline.json"' "$report" \
  >"$fixture_dir/report-with-offset-before-baseline.json"
if report_is_passed report-with-offset-before-baseline.json; then
  fail 'report whose publication offset is before its baseline was accepted'
fi
jq '.evidence.worker_loss_file = "worker-loss-forged.json"' "$report" \
  >"$fixture_dir/report-with-forged-delete-output.json"
jq '.delete_output = "pod/kafka-connect-other deletion requested"' "$worker_loss" \
  >"$fixture_dir/worker-loss-forged.json"
if report_is_passed report-with-forged-delete-output.json; then
  fail 'report whose deletion output names another Pod was accepted'
fi

observed_phases=()
record_phase() {
  observed_phases+=("$1")
}
connect_worker_loss_state_flow record_phase || fail 'valid state flow failed'
expected_phases='preflight observe-owner arm-target delete-target observe-reassignment publish-probe report verify restore'
observed_phase_line="$(printf '%s ' "${observed_phases[@]}")"
[[ "${observed_phase_line% }" == "$expected_phases" ]] ||
  fail "state flow order changed: $observed_phase_line"

observed_phases=()
fail_at_delete() {
  observed_phases+=("$1")
  [[ "$1" != delete-target ]]
}
if connect_worker_loss_state_flow fail_at_delete; then
  fail 'state flow accepted a failed deletion phase'
fi
observed_phase_line="$(printf '%s ' "${observed_phases[@]}")"
[[ "${observed_phase_line% }" == 'preflight observe-owner arm-target delete-target' ]] ||
  fail 'state flow continued after a failed deletion phase'

runtime_script="$script_dir/run-local-connect-worker-loss.sh"

dry_run_output="$(bash "$runtime_script" \
  --namespace simplematch-cert-run --namespace-run-id run-1 --dry-run)"
grep -Fq 'delete exactly that Connect Pod' <<<"$dry_run_output" ||
  fail 'runtime dry-run does not describe the fail-closed fault plan'
if bash "$runtime_script" --namespace simplematch-cert-run --namespace-run-id run-1 \
    --evidence-dir /tmp/simplematch-connect-worker-loss-absolute --dry-run >/dev/null 2>&1; then
  fail 'runtime accepted an absolute evidence path'
fi

printf '%s\n' 'Local Kafka Connect worker-loss contract passed.'

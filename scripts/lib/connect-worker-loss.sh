#!/usr/bin/env bash

# Kafka Connect worker-loss evidence Module.
#
# Interface:
#   connect_worker_loss_status_is_valid <status-file>
#   connect_worker_loss_pods_are_valid <pods-file>
#   connect_worker_loss_target_identity <status-file> <pods-file> <output-file>
#   connect_worker_loss_assert_reassignment <before-status> <after-status> \
#     <before-target> <after-target>
#   connect_worker_loss_report_is_valid <report-file>
#   connect_worker_loss_report_is_passed <report-file>
#
# A passed report must link every claim to immutable files in the report
# directory: status/Pod snapshots, the UID-guarded delete evidence, scoped
# provenance, and the shared CDC baseline/probe snapshots.
#
# The Module owns the interpretation of Connect task status and the mapping from a
# Connect worker id to a Ready Pod. The runner only supplies Kubernetes and REST
# Adapters, injects the Pod loss, and delegates CDC observation to cdc-verifier.sh.

# Version 2 adds scoped provenance fields and the executable verifier-contract
# evidence link. A report from version 1 is intentionally not upgraded.
CONNECT_WORKER_LOSS_REPORT_SCHEMA_VERSION=2
CONNECT_WORKER_LOSS_MAX_DEADLINE_SECONDS=900
CONNECT_WORKER_LOSS_MAX_IMAGE_CACHE_PREFLIGHT_SECONDS=120

connect_worker_loss_default_deadline_seconds() {
  printf '%s\n' 600
}

connect_worker_loss_setup_deadline_seconds() {
  printf '%s\n' 300
}

_connect_worker_loss_fail() {
  printf 'Connect worker-loss verifier: %s\n' "$*" >&2
  return 1
}

connect_worker_loss_status_is_valid() {
  local status_file="$1"

  [[ -s "$status_file" ]] ||
    _connect_worker_loss_fail "Connect status is missing or empty: $status_file" || return 1
  jq -e '
    (.name == "account-service-outbox") and
    (.connector.state == "RUNNING") and
    (.tasks | type == "array" and length == 1) and
    (.tasks[0].id | type == "number" and floor == . and . >= 0) and
    (.tasks[0].state == "RUNNING") and
    (.tasks[0].worker_id | type == "string" and length > 0)
  ' "$status_file" >/dev/null ||
    _connect_worker_loss_fail \
      "Connect status must contain one RUNNING task owned by a worker: $status_file" || return 1
}

connect_worker_loss_pods_are_valid() {
  local pods_file="$1"

  [[ -s "$pods_file" ]] ||
    _connect_worker_loss_fail "Connect Pod snapshot is missing or empty: $pods_file" || return 1
  jq -e '
    def ready:
      any(.status.conditions[]?; .type == "Ready" and .status == "True");
    [.items[]
      | select((.metadata.deletionTimestamp // null) == null)
      | select(ready)] as $ready |
    ($ready | length == 2) and
    ($ready | all(
      (.metadata.name | type == "string" and length > 0) and
      (.metadata.labels["app.kubernetes.io/name"] == "kafka-connect") and
      (.metadata.labels["app.kubernetes.io/component"] == "connector") and
      (.metadata.uid | type == "string" and length > 0) and
      (.spec.nodeName | type == "string" and length > 0) and
      (.status.podIP | type == "string" and length > 0) and
      ((.spec.volumes // []) | all(.persistentVolumeClaim == null))
    )) and
    (($ready | map(.spec.nodeName) | unique | length) == 2) and
    (($ready | map(.status.podIP) | unique | length) == 2)
  ' "$pods_file" >/dev/null ||
    _connect_worker_loss_fail \
      "Connect must have exactly two Ready, non-terminating Pods on distinct nodes without PVCs: $pods_file" || return 1
}

_connect_worker_loss_worker_host() {
  local worker_id="$1"

  if [[ "$worker_id" =~ ^([^:]+):[0-9]+$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  else
    printf '%s\n' "$worker_id"
  fi
}

_connect_worker_loss_owner_identity_is_valid() {
  local identity_file="$1" label="$2"

  [[ -s "$identity_file" ]] ||
    _connect_worker_loss_fail "$label task owner identity is missing: $identity_file" ||
    return 1
  jq -e '
    type == "object" and
    (.task_id | type == "number" and floor == . and . >= 0) and
    (.worker_id | type == "string" and length > 0) and
    (.worker_host | type == "string" and length > 0) and
    (.pod | type == "string" and length > 0) and
    (.pod_uid | type == "string" and length > 0) and
    (.node | type == "string" and length > 0) and
    (.pod_ip | type == "string" and length > 0) and
    (.ready == true)
  ' "$identity_file" >/dev/null ||
    _connect_worker_loss_fail "$label task owner identity is invalid: $identity_file" ||
    return 1
}

connect_worker_loss_target_identity() {
  local status_file="$1" pods_file="$2" output_file="$3"
  local worker_id worker_host task_id target_json

  [[ -n "$output_file" ]] ||
    _connect_worker_loss_fail 'target identity output path is required' || return 1
  connect_worker_loss_status_is_valid "$status_file" || return 1
  connect_worker_loss_pods_are_valid "$pods_file" || return 1

  worker_id="$(jq -er '.tasks[0].worker_id' "$status_file")" || return 1
  worker_host="$(_connect_worker_loss_worker_host "$worker_id")" || return 1
  task_id="$(jq -er '.tasks[0].id | tostring' "$status_file")" || return 1
  target_json="$(jq -c --arg worker_host "$worker_host" '
    def ready:
      any(.status.conditions[]?; .type == "Ready" and .status == "True");
    [.items[]
      | select((.metadata.deletionTimestamp // null) == null)
      | select(ready and .status.podIP == $worker_host)]
    | if length == 1 then .[0] else empty end
  ' "$pods_file")"
  [[ -n "$target_json" ]] ||
    _connect_worker_loss_fail \
      "Connect task worker $worker_id does not map to exactly one Ready Pod" || return 1

  jq -n \
    --arg worker_id "$worker_id" \
    --arg worker_host "$worker_host" \
    --argjson task_id "$task_id" \
    --argjson pod "$target_json" \
    '{task_id:$task_id,worker_id:$worker_id,worker_host:$worker_host,
      pod:$pod.metadata.name,pod_uid:$pod.metadata.uid,node:$pod.spec.nodeName,
      pod_ip:$pod.status.podIP,ready:true}' >"$output_file" || return 1
  _connect_worker_loss_owner_identity_is_valid "$output_file" target
}

connect_worker_loss_assert_reassignment() {
  local before_status="$1" after_status="$2" before_target="$3" after_target="$4"
  local before_worker after_worker before_task after_task before_uid after_uid
  local before_pod after_pod

  connect_worker_loss_status_is_valid "$before_status" || return 1
  connect_worker_loss_status_is_valid "$after_status" || return 1
  _connect_worker_loss_owner_identity_is_valid "$before_target" before || return 1
  _connect_worker_loss_owner_identity_is_valid "$after_target" after || return 1

  before_worker="$(jq -er '.tasks[0].worker_id' "$before_status")" || return 1
  after_worker="$(jq -er '.tasks[0].worker_id' "$after_status")" || return 1
  before_task="$(jq -er '.tasks[0].id' "$before_status")" || return 1
  after_task="$(jq -er '.tasks[0].id' "$after_status")" || return 1
  before_uid="$(jq -er '.pod_uid' "$before_target")" || return 1
  after_uid="$(jq -er '.pod_uid' "$after_target")" || return 1
  before_pod="$(jq -er '.pod' "$before_target")" || return 1
  after_pod="$(jq -er '.pod' "$after_target")" || return 1

  [[ "$before_task" == "$after_task" ]] ||
    _connect_worker_loss_fail \
      "Connect task id changed across worker loss: before=$before_task after=$after_task" || return 1
  [[ "$before_worker" != "$after_worker" ]] ||
    _connect_worker_loss_fail \
      'Connect REST status still reports the original task worker after Pod loss' || return 1
  [[ "$before_uid" != "$after_uid" && "$before_pod" != "$after_pod" ]] ||
    _connect_worker_loss_fail \
      'Connect task owner Pod identity did not change after worker loss' || return 1
  [[ "$(jq -r '.worker_id' "$before_target")" == "$before_worker" &&
    "$(jq -r '.worker_id' "$after_target")" == "$after_worker" ]] ||
    _connect_worker_loss_fail \
      'task owner identity snapshots disagree with Connect REST status' || return 1
}

connect_worker_loss_report_is_valid() {
  local report_file="$1"

  [[ -s "$report_file" ]] ||
    _connect_worker_loss_fail "worker-loss report is missing or empty: $report_file" || return 1
  jq -e \
    --argjson schema_version "$CONNECT_WORKER_LOSS_REPORT_SCHEMA_VERSION" \
    --argjson max_deadline "$CONNECT_WORKER_LOSS_MAX_DEADLINE_SECONDS" '
      .schema_version == $schema_version and
      .profile == "connect-worker-loss" and
      (.status == "PASSED" or .status == "FAILED" or .status == "UNSUPPORTED") and
      (.cluster | type == "string" and length > 0) and
      (.context | type == "string" and length > 0) and
      (.namespace | type == "string" and length > 0) and
      (.namespace_run_id | type == "string" and length > 0) and
      (.run_id | type == "string" and length > 0) and
      (.fault_mode == "pod-delete") and
      (.deadline_seconds | type == "number" and floor == . and
        . >= 1 and . <= $max_deadline) and
      (.recovery_deadline_started_at_unix_ms | type == "number" and
        floor == . and . >= 0) and
      (.claim_boundary | type == "array" and length > 0 and all(.[]; type == "string" and length > 0)) and
      ((.failure_reason == null) or (.failure_reason | type == "string")) and
      ((.status == "PASSED" and (.failure_reason == null or .failure_reason == "")) or
       (.status != "PASSED" and (.failure_reason | type == "string" and length > 0)))
    ' "$report_file" >/dev/null ||
    _connect_worker_loss_fail "worker-loss report envelope is invalid: $report_file" || return 1
}

connect_worker_loss_image_cache_evidence_is_valid() {
  local evidence_file="$1"
  local deployment_file="$2"
  local image_reference

  [[ -s "$evidence_file" && -s "$deployment_file" ]] ||
    _connect_worker_loss_fail 'image-cache preflight evidence is missing or empty' || return 1
  image_reference="$(jq -er '
    [.spec.template.spec.containers[]? | select(.name == "kafka-connect") | .image]
    | if length == 1 and (.[0] | type == "string" and length > 0) then .[0]
      else empty end
  ' "$deployment_file")" ||
    _connect_worker_loss_fail 'Connect Deployment does not identify exactly one image' || return 1
  jq -e --arg image "$image_reference" \
    --argjson max_budget "$CONNECT_WORKER_LOSS_MAX_IMAGE_CACHE_PREFLIGHT_SECONDS" '
      .schema_version == 1 and .status == "PASS" and
      .image_reference == $image and
      (.image_identity | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
      (.budget_seconds | type == "number" and floor == . and . >= 1 and . <= $max_budget) and
      (.nodes | type == "array" and length >= 2) and
      ((.nodes | map(.node) | unique | length) == (.nodes | length)) and
      (.nodes | all(
        (.node | type == "string" and length > 0) and
        .status == "PASS" and .inspect_status == "PASS" and
        .execution_probe_status == "PASS" and
        (.identity | type == "string" and test("^sha256:[0-9a-f]{64}$"))
      )) and
      ((.nodes | map(.identity) | unique | length) == 1) and
      (.nodes[0].identity == .image_identity) and
      (.failure_reason == null or .failure_reason == "")
    ' "$evidence_file" >/dev/null ||
    _connect_worker_loss_fail 'image-cache preflight evidence is invalid' || return 1
}

_connect_worker_loss_report_evidence_path() {
  local report_file="$1" key="$2" report_dir relative_path

  case "$report_file" in
    /*|..|../*|*/../*|*/..)
      _connect_worker_loss_fail 'worker-loss report path must be relative and local' || return 1
      ;;
  esac
  report_dir="${report_file%/*}"
  [[ "$report_dir" == "$report_file" ]] && report_dir=.
  relative_path="$(jq -er --arg key "$key" \
    '.evidence[$key] | select(type == "string" and length > 0)' "$report_file")" || return 1
  case "$relative_path" in
    /*|..|../*|*/../*|*/..)
      _connect_worker_loss_fail "report evidence path is not relative and local: $key" || return 1
      ;;
  esac
  printf '%s/%s\n' "$report_dir" "$relative_path"
}

connect_worker_loss_prerequisites_are_valid() {
  local report_file="$1" nodes deployment pdb postgres connect_config account_config risk_config
  local connect_pods
  local topic job connector config_file table
  local -a prerequisite_keys=(
    nodes_file control_plane_readyz_file control_plane_before_file
    control_plane_after_file control_plane_events_file
    connect_deployment_file connect_pdb_file connect_config_file
    image_cache_preflight_file
    account_connector_file risk_connector_file postgres_file
    topic_provisioning_file account_flyway_file risk_flyway_file
    persistence_flyway_file market_data_projection_flyway_file
    query_flyway_file quickfix_gateway_flyway_file
    connect_configs_topic_file connect_offsets_topic_file connect_status_topic_file
  )
  local -A prerequisite_paths=()

  for key in "${prerequisite_keys[@]}"; do
    local path
    path="$(_connect_worker_loss_report_evidence_path "$report_file" "$key")" || return 1
    [[ -f "$path" && ! -L "$path" ]] ||
      _connect_worker_loss_fail "prerequisite evidence file is missing: $key" || return 1
    prerequisite_paths["$key"]="$path"
  done
  nodes="${prerequisite_paths[nodes_file]}"
  local control_plane_readyz="${prerequisite_paths[control_plane_readyz_file]}"
  local control_plane_before="${prerequisite_paths[control_plane_before_file]}"
  local control_plane_after="${prerequisite_paths[control_plane_after_file]}"
  local control_plane_events="${prerequisite_paths[control_plane_events_file]}"
  deployment="${prerequisite_paths[connect_deployment_file]}"
  pdb="${prerequisite_paths[connect_pdb_file]}"
  connect_config="${prerequisite_paths[connect_config_file]}"
  account_config="${prerequisite_paths[account_connector_file]}"
  risk_config="${prerequisite_paths[risk_connector_file]}"
  postgres="${prerequisite_paths[postgres_file]}"

  jq -e '
    [.items[]
      | select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience")
      | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))]
    | length >= 2
  ' "$nodes" >/dev/null ||
    _connect_worker_loss_fail 'prerequisite node evidence has fewer than two Ready resilience workers' || return 1
  grep -Fq 'readyz check passed' "$control_plane_readyz" ||
    _connect_worker_loss_fail 'control-plane readyz evidence is not successful' || return 1
  jq -e 'length == 3 and all(.[]; .phase == "Running" and .ready == true)' \
    "$control_plane_before" >/dev/null ||
    _connect_worker_loss_fail 'control-plane before snapshot is not fully Ready' || return 1
  jq -e 'length == 3 and all(.[]; .phase == "Running" and .ready == true)' \
    "$control_plane_after" >/dev/null ||
    _connect_worker_loss_fail 'control-plane after snapshot is not fully Ready' || return 1
  jq -n -e --slurpfile before "$control_plane_before" --slurpfile after "$control_plane_after" \
    '$before[0] == $after[0]' >/dev/null ||
    _connect_worker_loss_fail 'control-plane restart/readiness snapshot changed during the gate' || return 1
  jq -e '.items | type == "array"' "$control_plane_events" >/dev/null ||
    _connect_worker_loss_fail 'control-plane event evidence is malformed' || return 1
  jq -e '
    .spec.replicas == 2 and
    (.spec.template.spec.volumes // [] | all(.persistentVolumeClaim == null)) and
    any(.spec.template.spec.containers[]?;
      .name == "kafka-connect" and .image == "quay.io/debezium/connect:3.6.0.Final")
  ' "$deployment" >/dev/null ||
    _connect_worker_loss_fail 'Connect Deployment prerequisite evidence is invalid' || return 1
  connect_worker_loss_image_cache_evidence_is_valid \
    "${prerequisite_paths[image_cache_preflight_file]}" "$deployment" || return 1
  jq -e '
    (.spec.template.spec.containers[] | select(.name == "kafka-connect")) as $container |
    ($container.env | map({key:.name,value:(.value // null)}) | from_entries) as $env |
    (.spec.template.spec.tolerations // []) as $tolerations |
    (.spec.template.spec.topologySpreadConstraints // []) as $spreads |
    $env.GROUP_ID == "simplematch-connect-local" and
    $env.CONFIG_STORAGE_TOPIC == "simplematch-connect-configs" and
    $env.OFFSET_STORAGE_TOPIC == "simplematch-connect-offsets" and
    $env.STATUS_STORAGE_TOPIC == "simplematch-connect-status" and
    $env.CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR == "3" and
    $env.CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR == "3" and
    $env.CONNECT_STATUS_STORAGE_REPLICATION_FACTOR == "3" and
    $env.CONNECT_CONFIG_PROVIDERS == "envvarprovider" and
    $env.CONNECT_CONFIG_PROVIDERS_ENVVARPROVIDER_CLASS ==
      "org.apache.kafka.common.config.provider.EnvVarConfigProvider" and
    .spec.template.spec.nodeSelector["simplematch.io/node-pool"] == "local-resilience" and
    any($spreads[]?;
      .maxSkew == 1 and .topologyKey == "simplematch.io/worker-slot" and
      .whenUnsatisfiable == "DoNotSchedule" and
      .labelSelector.matchLabels["app.kubernetes.io/name"] == "kafka-connect" and
      .labelSelector.matchLabels["app.kubernetes.io/component"] == "connector") and
    any($tolerations[]?;
      .key == "simplematch.io/portable-workload" and .operator == "Exists" and
      .effect == "NoExecute" and .tolerationSeconds == 30) and
    any($tolerations[]?;
      .key == "node.kubernetes.io/not-ready" and .operator == "Exists" and
      .effect == "NoExecute" and .tolerationSeconds == 30) and
    any($tolerations[]?;
      .key == "node.kubernetes.io/unreachable" and .operator == "Exists" and
      .effect == "NoExecute" and .tolerationSeconds == 30)
  ' "$deployment" >/dev/null ||
    _connect_worker_loss_fail 'Connect Deployment does not prove its profile, spread, or tolerations' || return 1
  jq -e '
    .metadata.name == "simplematch-kafka-connect-config" and
    .data.bootstrap_servers == "kafka:9092" and
    .data.postgres_hostname == "postgres" and
    .data.postgres_port == "5432" and
    .data.postgres_dbname == "simplematch" and
    .data.postgres_sslmode == "disable" and
    .data.postgres_sslrootcert == "/dev/null"
  ' "$connect_config" >/dev/null ||
    _connect_worker_loss_fail 'Kafka Connect profile ConfigMap evidence is invalid' || return 1
  jq -e '
    .spec.minAvailable == 1 and
    .spec.selector.matchLabels["app.kubernetes.io/name"] == "kafka-connect" and
    .spec.selector.matchLabels["app.kubernetes.io/component"] == "connector"
  ' "$pdb" >/dev/null ||
    _connect_worker_loss_fail 'Connect PDB prerequisite evidence is invalid' || return 1
  connect_pods="$(_connect_worker_loss_report_evidence_path \
    "$report_file" pods_before_file)" || return 1
  connect_worker_loss_pods_are_valid "$connect_pods" || return 1
  jq -n -e --slurpfile nodes "$nodes" --slurpfile pods "$connect_pods" '
    ($nodes[0].items // []) as $node_items |
    ($pods[0].items // []
      | map(select(any(.status.conditions[]?; .type == "Ready" and .status == "True")))) as $pod_items |
    ($node_items
      | map(select(.metadata.labels["simplematch.io/node-pool"] == "local-resilience"))
      | map({name:.metadata.name,slot:(.metadata.labels["simplematch.io/worker-slot"] // "")})
      | map(select(.slot | test("^[0-9]+$")))) as $worker_nodes |
    ($pod_items | map(.spec.nodeName)) as $pod_nodes |
    ($worker_nodes | map(.slot) | unique | length) >= 2 and
    ($pod_nodes | length == 2 and (unique | length) == 2) and
    all($pod_nodes[]; . as $pod_node |
      any($worker_nodes[]; .name == $pod_node))
  ' >/dev/null ||
    _connect_worker_loss_fail 'Connect worker Pods are not bound to distinct labelled resilience slots' || return 1
  jq -e '
    .kind == "StatefulSet" and .metadata.name == "postgres" and
    (.status.readyReplicas // 0) >= (.spec.replicas // 1)
  ' "$postgres" >/dev/null ||
    _connect_worker_loss_fail 'PostgreSQL prerequisite evidence is invalid' || return 1

  for connector in account-service-outbox risk-service-outbox; do
    config_file="$account_config"
    table='account_service.outbox'
    if [[ "$connector" == risk-service-outbox ]]; then
      config_file="$risk_config"
      table='risk_service.outbox'
    fi
    jq -e --arg connector "$connector" --arg table "$table" '
      (.data["connector.json"] | fromjson) as $document |
      ($document.name == $connector) and
      ($document.config["table.include.list"] == $table) and
      ($document.config["transforms.outbox.table.fields.additional.placement"]
        | contains("headers_json:header:headers_json")) and
      ($document.config["transforms.outbox.table.fields.additional.placement"]
        | contains("payload_type:header:eventType"))
    ' "$config_file" >/dev/null ||
      _connect_worker_loss_fail "$connector connector prerequisite evidence is invalid" || return 1
  done

  for key in topic_provisioning_file account_flyway_file risk_flyway_file \
      persistence_flyway_file market_data_projection_flyway_file query_flyway_file \
      quickfix_gateway_flyway_file; do
    job="${prerequisite_paths[$key]}"
    jq -e '.kind == "Job" and any(.status.conditions[]?; .type == "Complete" and .status == "True")' \
      "$job" >/dev/null ||
      _connect_worker_loss_fail "Kubernetes prerequisite Job evidence is incomplete: $job" || return 1
  done
  for key in connect_configs_topic_file connect_offsets_topic_file connect_status_topic_file; do
    topic="${prerequisite_paths[$key]}"
    grep -Fq 'ReplicationFactor: 3' "$topic" ||
      _connect_worker_loss_fail "Kafka internal topic evidence is not RF3: $topic" || return 1
    grep -Eq 'min\.insync\.replicas[=:]2' "$topic" ||
      _connect_worker_loss_fail "Kafka internal topic evidence does not require ISR2: $topic" || return 1
  done
}

connect_worker_loss_report_is_passed() {
  local report_file="$1"
  local before_status after_status before_pods after_pods before_target after_target
  local pre_delete_status pre_delete_pods pre_delete_target pre_delete_pod worker_loss provenance
  local transition publication_evidence
  local pod_patch pod_delete_precondition delete_observation baseline probe kafka_baseline verifier_contract evidence_file
  local verifier_contract_sha256 actual_contract_sha256
  local verifier_contract_script verifier_contract_evidence_file
  local verifier_observer_script verifier_observer_evidence_file
  local verifier_observer_sha256
  local before_pod before_uid pre_delete_uid
  local report_event probe_event transition_event transition_aggregate transition_payload_type
  local transition_created_at reassignment_observed_at
  local publication_partition publication_offset
  local recovery_deadline_started_at_unix_ms fault_requested_at_unix_ms
  local run_id namespace namespace_run_id

  connect_worker_loss_report_is_valid "$report_file" || return 1
  before_status="$(_connect_worker_loss_report_evidence_path "$report_file" status_before_file)" || return 1
  after_status="$(_connect_worker_loss_report_evidence_path "$report_file" status_after_file)" || return 1
  before_pods="$(_connect_worker_loss_report_evidence_path "$report_file" pods_before_file)" || return 1
  after_pods="$(_connect_worker_loss_report_evidence_path "$report_file" pods_after_file)" || return 1
  before_target="$(_connect_worker_loss_report_evidence_path "$report_file" target_before_file)" || return 1
  after_target="$(_connect_worker_loss_report_evidence_path "$report_file" target_after_file)" || return 1
  pre_delete_status="$(_connect_worker_loss_report_evidence_path "$report_file" status_pre_delete_file)" || return 1
  pre_delete_pods="$(_connect_worker_loss_report_evidence_path "$report_file" pods_pre_delete_file)" || return 1
  pre_delete_target="$(_connect_worker_loss_report_evidence_path "$report_file" target_pre_delete_file)" || return 1
  pre_delete_pod="$(_connect_worker_loss_report_evidence_path "$report_file" pod_pre_delete_file)" || return 1
  pod_patch="$(_connect_worker_loss_report_evidence_path "$report_file" pod_patch_file)" || return 1
  pod_delete_precondition="$(_connect_worker_loss_report_evidence_path \
    "$report_file" pod_delete_precondition_file)" || return 1
  delete_observation="$(_connect_worker_loss_report_evidence_path \
    "$report_file" target_delete_observation_file)" || return 1
  worker_loss="$(_connect_worker_loss_report_evidence_path "$report_file" worker_loss_file)" || return 1
  provenance="$(_connect_worker_loss_report_evidence_path "$report_file" provenance_file)" || return 1
  transition="$(_connect_worker_loss_report_evidence_path "$report_file" transition_file)" || return 1
  baseline="$(_connect_worker_loss_report_evidence_path "$report_file" baseline_file)" || return 1
  probe="$(_connect_worker_loss_report_evidence_path "$report_file" probe_file)" || return 1
  kafka_baseline="$(_connect_worker_loss_report_evidence_path "$report_file" kafka_baseline_file)" || return 1
  publication_evidence="$(_connect_worker_loss_report_evidence_path \
    "$report_file" publication_evidence_file)" || return 1
  verifier_contract="$(_connect_worker_loss_report_evidence_path \
    "$report_file" verifier_contract_file)" || return 1
  verifier_contract_script="$(_connect_worker_loss_report_evidence_path \
    "$report_file" verifier_contract_script_file)" || return 1
  verifier_observer_script="$(_connect_worker_loss_report_evidence_path \
    "$report_file" verifier_observer_script_file)" || return 1
  for evidence_file in "$before_status" "$after_status" "$before_pods" "$after_pods" \
      "$before_target" "$after_target" "$pre_delete_status" "$pre_delete_pods" \
      "$pre_delete_target" \
      "$pre_delete_pod" "$pod_patch" "$pod_delete_precondition" "$delete_observation" "$worker_loss" "$provenance" "$transition" \
      "$baseline" "$probe" "$kafka_baseline" "$publication_evidence" \
      "$verifier_contract" "$verifier_contract_script" "$verifier_observer_script"; do
    [[ -f "$evidence_file" && ! -L "$evidence_file" ]] ||
      _connect_worker_loss_fail "report evidence file is missing: $evidence_file" || return 1
  done
  grep -Fxq 'CDC observer fixture header contract is valid.' "$verifier_contract" ||
    _connect_worker_loss_fail 'verifier contract evidence does not prove the current fixture contract' ||
    return 1
  jq -e '.verifier_contract_path |
    select(type == "string" and length > 0)' "$provenance" >/dev/null ||
    _connect_worker_loss_fail \
    'provenance does not identify the verifier contract executable' || return 1
  verifier_contract_evidence_file="$(jq -er \
    '.verifier_contract_evidence_file |
      select(type == "string" and length > 0)' "$provenance")" ||
    _connect_worker_loss_fail \
      'provenance does not identify its retained verifier contract copy' || return 1
  [[ "$verifier_contract_evidence_file" == \
    "$(jq -er '.evidence.verifier_contract_script_file' "$report_file")" ]] ||
    _connect_worker_loss_fail \
      'provenance verifier contract copy is not linked from the report' || return 1
  verifier_contract_sha256="$(jq -er \
    '.verifier_contract_sha256 | select(type == "string" and test("^[0-9a-f]{64}$"))' \
    "$provenance")" || _connect_worker_loss_fail \
    'provenance does not contain a canonical verifier contract digest' || return 1
  actual_contract_sha256="$(sha256sum "$verifier_contract_script" | awk '{print $1}')" ||
    return 1
  [[ "$actual_contract_sha256" == "$verifier_contract_sha256" ]] ||
    _connect_worker_loss_fail \
      'provenance verifier contract digest does not match its retained copy' || return 1
  verifier_observer_evidence_file="$(jq -er \
    '.verifier_observer_evidence_file |
      select(type == "string" and length > 0)' "$provenance")" ||
    _connect_worker_loss_fail \
      'provenance does not identify its retained CDC observer copy' || return 1
  [[ "$verifier_observer_evidence_file" == \
    "$(jq -er '.evidence.verifier_observer_script_file' "$report_file")" ]] ||
    _connect_worker_loss_fail \
      'provenance CDC observer copy is not linked from the report' || return 1
  verifier_observer_sha256="$(jq -er \
    '.verifier_observer_sha256 | select(type == "string" and test("^[0-9a-f]{64}$"))' \
    "$provenance")" || _connect_worker_loss_fail \
    'provenance does not contain a canonical CDC observer digest' || return 1
  [[ "$(sha256sum "$verifier_observer_script" | awk '{print $1}')" == \
    "$verifier_observer_sha256" ]] || _connect_worker_loss_fail \
    'provenance CDC observer digest does not match its retained copy' || return 1

  connect_worker_loss_prerequisites_are_valid "$report_file" || return 1
  connect_worker_loss_status_is_valid "$before_status" || return 1
  connect_worker_loss_status_is_valid "$after_status" || return 1
  connect_worker_loss_status_is_valid "$pre_delete_status" || return 1
  connect_worker_loss_pods_are_valid "$before_pods" || return 1
  connect_worker_loss_pods_are_valid "$pre_delete_pods" || return 1
  connect_worker_loss_pods_are_valid "$after_pods" || return 1
  connect_worker_loss_target_identity "$pre_delete_status" "$pre_delete_pods" \
    "$pre_delete_target" >/dev/null || return 1
  connect_worker_loss_assert_reassignment \
    "$before_status" "$after_status" "$before_target" "$after_target" || return 1

  run_id="$(jq -er '.run_id' "$report_file")" || return 1
  namespace="$(jq -er '.namespace' "$report_file")" || return 1
  namespace_run_id="$(jq -er '.namespace_run_id' "$report_file")" || return 1
  recovery_deadline_started_at_unix_ms="$(jq -er \
    '.recovery_deadline_started_at_unix_ms |
      select(type == "number" and floor == . and . > 0)' "$report_file")" ||
    _connect_worker_loss_fail \
      'passed worker-loss report has no positive recovery deadline start' || return 1
  fault_requested_at_unix_ms="$(jq -er \
    '.requested_at_unix_ms | select(type == "number" and floor == . and . > 0)' \
    "$worker_loss")" ||
    _connect_worker_loss_fail \
      'worker-loss evidence has no positive fault request timestamp' || return 1
  before_pod="$(jq -er '.pod' "$before_target")" || return 1
  before_uid="$(jq -er '.pod_uid' "$before_target")" || return 1
  pre_delete_uid="$(jq -er '.metadata.uid' "$pre_delete_pod")" || return 1
  jq -e --slurpfile before "$before_target" '
    .task_id == $before[0].task_id and .worker_id == $before[0].worker_id and
    .pod == $before[0].pod and .pod_uid == $before[0].pod_uid and
    .worker_host == $before[0].worker_host and .pod_ip == $before[0].pod_ip
  ' "$pre_delete_target" >/dev/null ||
    _connect_worker_loss_fail 'pre-delete task owner evidence changed before deletion' || return 1
  jq -e --arg pod "$before_pod" --arg uid "$before_uid" --arg pre_uid "$pre_delete_uid" '
    .metadata.name == $pod and .metadata.uid == $uid and .metadata.uid == $pre_uid and
    (.metadata.deletionTimestamp // null) == null and
    any(.status.conditions[]?; .type == "Ready" and .status == "True")
  ' "$pre_delete_pod" >/dev/null ||
    _connect_worker_loss_fail 'pre-delete Pod evidence does not identify the task owner' || return 1
  jq -e --arg pod "$before_pod" --arg uid "$before_uid" --arg run_id "$run_id" '
    .metadata.name == $pod and .metadata.uid == $uid and
    .metadata.labels["simplematch.io/worker-loss-run"] == $run_id
  ' "$pod_patch" >/dev/null ||
    _connect_worker_loss_fail 'UID-guarded marker patch evidence does not identify the task owner' || return 1
  jq -e --arg pod "$before_pod" --arg uid "$before_uid" --arg run_id "$run_id" '
    .metadata.name == $pod and .metadata.uid == $uid and
    .metadata.labels["simplematch.io/worker-loss-run"] == $run_id and
    (.metadata.deletionTimestamp // null) == null
  ' "$pod_delete_precondition" >/dev/null ||
    _connect_worker_loss_fail 'UID delete precondition evidence does not identify the task owner' || return 1
  jq -e --arg pod "$before_pod" --arg uid "$before_uid" '
    .schema_version == 1 and .target_pod == $pod and
    .target_pod_uid == $uid and .target_uid_absent == true and
    (.outcome == "not-found" or
      (.outcome == "replacement-pod" and
       (.replacement_pod_uid | type == "string" and length > 0 and . != $uid))) and
    (.observed_at_utc | type == "string" and length > 0)
  ' "$delete_observation" >/dev/null ||
    _connect_worker_loss_fail 'Pod deletion observation does not prove the original UID disappeared' || return 1
  jq -e --arg uid "$before_uid" --arg pod "$before_pod" \
      'all(.items[]?; .metadata.uid != $uid and .metadata.name != $pod)' \
      "$after_pods" >/dev/null ||
    _connect_worker_loss_fail 'after-reassignment Pods still contain the deleted task owner' || return 1

  jq -e --arg pod "$before_pod" --arg uid "$before_uid" --arg run_id "$run_id" \
      --argjson recovery_started "$recovery_deadline_started_at_unix_ms" \
      --argjson requested_at "$fault_requested_at_unix_ms" '
    .fault == "pod-delete" and .target_pod == $pod and .target_pod_uid == $uid and
    .delete_requested == true and .uid_precondition_test == true and .pre_delete_recheck == true and
    .target_uid_absent == true and .delete_output_contains_target == true and
    .delete_selector == ("simplematch.io/worker-loss-run=" + $run_id) and
    (.delete_output | type == "string" and contains($pod)) and
    .recovery_deadline_started_at_unix_ms == $recovery_started and
    (.recovery_deadline_started_at_unix_ms | type == "number" and floor == . and . > 0) and
    .requested_at_unix_ms == $requested_at and
    (.requested_at_unix_ms | type == "number" and floor == . and . >= $recovery_started)
  ' "$worker_loss" >/dev/null ||
    _connect_worker_loss_fail 'worker-loss evidence does not prove an exact Pod deletion' || return 1

  jq -e --arg namespace "$namespace" --arg namespace_run_id "$namespace_run_id" '
    .status == "PASS" and .namespace == $namespace and
    .namespace_run_id == $namespace_run_id and
    (.current_commit | type == "string" and test("^[0-9a-f]{40}$")) and
    (.cdc_runtime_signature | type == "string" and test("^[0-9a-f]{64}$")) and
    (.retained_cdc_runtime_signature | type == "string" and test("^[0-9a-f]{64}$")) and
    .cdc_runtime_signature == .retained_cdc_runtime_signature and
    (.cdc_verifier_signature | type == "string" and test("^[0-9a-f]{64}$")) and
    (.retained_cdc_verifier_signature | type == "string" and test("^[0-9a-f]{64}$")) and
    (.verifier_signature_changed | type == "boolean") and
    (.verifier_signature_changed ==
      (.cdc_verifier_signature != .retained_cdc_verifier_signature)) and
    .runtime_reused == true and
    (.verifier_image_identity | type == "string" and
      test("^sha256:[0-9a-f]{64}$")) and
    (.verifier_observer_path | type == "string" and length > 0) and
    (.verifier_observer_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.verifier_observer_evidence_file | type == "string" and length > 0) and
    (.verifier_contract_path | type == "string" and length > 0) and
    (.verifier_contract_sha256 | type == "string" and test("^[0-9a-f]{64}$"))
  ' "$provenance" >/dev/null ||
    _connect_worker_loss_fail 'provenance evidence does not match the worker-loss report' || return 1

  report_event="$(jq -er '.publication.event_id' "$report_file")" || return 1
  transition_event="$(jq -er '.event_id' "$transition")" || return 1
  transition_aggregate="$(jq -er '.aggregate_id' "$transition")" || return 1
  transition_payload_type="$(jq -er '.payload_type' "$transition")" || return 1
  transition_created_at="$(jq -er '.transition_created_at_unix_ms' "$transition")" || return 1
  reassignment_observed_at="$(jq -er '.reassignment_observed_at_unix_ms' "$transition")" || return 1
  jq -e --arg event_id "$report_event" --arg aggregate_id "$(jq -er '.aggregate_id' "$baseline")" \
      --arg payload_type "$(jq -er '.payload_type' "$probe")" \
      --argjson transition_created_at "$transition_created_at" \
      --argjson reassignment_observed_at "$reassignment_observed_at" '
    .schema_version == 1 and
    (.aggregate_id | type == "string" and length > 0) and
    .aggregate_id == $aggregate_id and .event_id == $event_id and
    .payload_type == $payload_type and
    (.transition_created_at_unix_ms | type == "number" and floor == . and . >= 0) and
    (.reassignment_observed_at_unix_ms | type == "number" and floor == . and . >= 0) and
    $transition_created_at == .transition_created_at_unix_ms and
    $reassignment_observed_at == .reassignment_observed_at_unix_ms and
    .transition_created_at_unix_ms > .reassignment_observed_at_unix_ms and
    (.transition | type == "string" and length > 0)
  ' "$transition" >/dev/null ||
    _connect_worker_loss_fail 'Account transition evidence is not linked to reassignment and baseline' || return 1
  jq -e --arg event_id "$transition_event" --arg aggregate_id "$transition_aggregate" \
      --arg payload_type "$transition_payload_type" --argjson created_at "$transition_created_at" '
    .event_id == $event_id and .business_identity == $aggregate_id and
    .payload_type == $payload_type and .created_at_unix_ms == $created_at
  ' "$probe" >/dev/null ||
    _connect_worker_loss_fail 'outbox probe is not linked to the Account transition evidence' || return 1
  declare -F cdc_validate_publication_evidence >/dev/null ||
    _connect_worker_loss_fail 'shared CDC publication evidence validator is unavailable' || return 1
  cdc_validate_publication_evidence "$publication_evidence" || return 1
  jq -e --arg event_id "$report_event" --arg topic "$(jq -er '.topic' "$probe")" \
      --arg message_key "$(jq -er '.message_key' "$probe")" \
      --argjson timestamp "$(jq -er '.created_at_unix_ms' "$probe")" \
      --arg headers_sha "$(printf '%s' "$(jq -er '.headers_json' "$probe")" |
        sha256sum | awk '{print $1}')" \
      --arg event_type "$(jq -er '.payload_type' "$probe")" \
      --arg payload_sha "$(jq -er '.payload_sha256' "$probe")" '
    .event_id == $event_id and .topic == $topic and
    .expected_message_key == $message_key and
    .expected_timestamp_unix_ms == $timestamp and
    .expected_headers_json_sha256 == $headers_sha and
    .expected_event_type == $event_type and
    .expected_payload_sha256 == $payload_sha and
    .verification.headers_exact == true and
    .verification.key_exact == true and
    .verification.timestamp_exact == true and
    .verification.payload_exact == true
  ' "$publication_evidence" >/dev/null ||
    _connect_worker_loss_fail 'Kafka publication evidence is not linked to the verified outbox probe' || return 1
  publication_partition="$(jq -er '.partition' "$publication_evidence")" || return 1
  publication_offset="$(jq -er '.offset' "$publication_evidence")" || return 1
  awk -F '\t' -v partition="$publication_partition" -v offset="$publication_offset" '
    $1 == partition && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ && offset >= $2 { found = 1 }
    END { exit(found ? 0 : 1) }
  ' "$kafka_baseline" ||
    _connect_worker_loss_fail 'Kafka publication location is not after its retained baseline' || return 1
  jq -e --arg event_id "$report_event" '
    .schema_version == 1 and (.schema == "account_service") and
    (.aggregate_type == "account_reservation") and
    (.aggregate_id | type == "string" and length > 0) and
    (.event_ids | type == "array") and
    (.event_ids | all(.[]; type == "string" and
      test("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) and
    ((.event_ids | unique | length) == (.event_ids | length)) and
    (($event_id as $id | .event_ids | index($id)) == null)
  ' "$baseline" >/dev/null ||
    _connect_worker_loss_fail 'outbox baseline evidence is malformed or already contains the transition' || return 1
  declare -F cdc_validate_probe >/dev/null ||
    _connect_worker_loss_fail 'shared CDC probe validator is unavailable' || return 1
  cdc_validate_probe "$probe" || return 1
  probe_event="$(jq -er '.event_id' "$probe")" || return 1
  [[ "$probe_event" == "$report_event" ]] ||
    _connect_worker_loss_fail 'report publication event does not match the retained outbox probe' || return 1
  [[ -s "$kafka_baseline" ]] ||
    _connect_worker_loss_fail 'Kafka baseline evidence is empty' || return 1

  jq -e --slurpfile before "$before_target" --slurpfile after "$after_target" '
    .status == "PASSED" and
    .prerequisites.connect_workers == 2 and
    .prerequisites.ready_workers_before == 2 and
    .prerequisites.ready_workers_after == 2 and
    .prerequisites.internal_topics_rf3 == true and
    .prerequisites.pdb_min_available_1 == true and
    .prerequisites.connect_has_no_pvc == true and
    .prerequisites.service_owned_connectors == true and
    .prerequisites.flyway_and_topic_prerequisites == true and
    (.task_reassignment.connector == "account-service-outbox") and
    (.task_reassignment.before == $before[0]) and
    (.task_reassignment.after == $after[0]) and
    (.task_reassignment.task_id == $after[0].task_id) and
    (.task_reassignment.task_id_unchanged == true) and
    (.task_reassignment.worker_id_changed == true) and
    (.task_reassignment.pod_uid_changed == true) and
    (.publication.baseline_captured == true) and
    (.publication.post_transition_probe == true) and
    (.publication.exact_kafka_record == true) and
    (.publication.transition_after_reassignment == true) and
    (.publication.transition_file == .evidence.transition_file) and
    (.publication.publication_evidence_file == .evidence.publication_evidence_file) and
    (.publication.baseline_file == .evidence.baseline_file) and
    (.publication.probe_file == .evidence.probe_file) and
    (.publication.kafka_baseline_file == .evidence.kafka_baseline_file) and
    (.publication.event_id | type == "string" and
      test("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) and
    (.failure_reason == null or .failure_reason == "") and
    (.evidence | type == "object")
  ' "$report_file" >/dev/null ||
    _connect_worker_loss_fail "worker-loss report does not prove Connect reassignment and CDC publication: $report_file" || return 1
}

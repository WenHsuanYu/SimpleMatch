#!/usr/bin/env ruby
# frozen_string_literal: true

require "digest"
require "json"
require "pathname"

class InvalidWorkerLossEvidence < StandardError; end

module WorkerLossChecks
  SHA256 = /\A[0-9a-f]{64}\z/
  IMAGE_ID = /\Asha256:[0-9a-f]{64}\z/
  COMMIT = /\A[0-9a-f]{40}\z/
  UUID = /\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/i

  module_function

  def assert(condition, message)
    raise InvalidWorkerLossEvidence, message unless condition
  end

  def object(value, label)
    assert(value.is_a?(Hash), "#{label} must be an object")
    value
  end

  def array(value, label)
    assert(value.is_a?(Array), "#{label} must be an array")
    value
  end

  def string(value, label)
    assert(value.is_a?(String) && !value.empty?, "#{label} must be a non-empty string")
    value
  end

  def integer(value, label, minimum: nil, maximum: nil)
    assert(value.is_a?(Integer), "#{label} must be an integer")
    assert(minimum.nil? || value >= minimum, "#{label} is below its minimum")
    assert(maximum.nil? || value <= maximum, "#{label} exceeds its maximum")
    value
  end

end

# A frozen named carrier keeps this verifier compatible with the repository's
# existing Ruby runtime instead of introducing a Ruby 3.2-only dependency.
EvidenceBundle = Struct.new(:report_path, :report, :paths) do
  PREREQUISITE_FILES = {
    "nodes_file" => "nodes.json",
    "control_plane_readyz_file" => "control-plane/readyz.txt",
    "control_plane_before_file" => "control-plane/before.json",
    "control_plane_after_file" => "control-plane/after.json",
    "control_plane_events_file" => "control-plane/events.json",
    "connect_deployment_file" => "connect-deployment.json",
    "connect_pdb_file" => "connect-pdb.json",
    "connect_config_file" => "connect-config.json",
    "image_cache_preflight_file" => "image-cache-preflight.json",
    "pods_before_file" => "connect-pods-before.json",
    "account_connector_file" => "account-service-outbox-configmap.json",
    "risk_connector_file" => "risk-service-outbox-configmap.json",
    "postgres_file" => "prerequisites/postgres.json",
    "topic_provisioning_file" => "prerequisites/kafka-topic-provisioning.json",
    "account_flyway_file" => "prerequisites/account-service-flyway.json",
    "risk_flyway_file" => "prerequisites/risk-service-flyway.json",
    "persistence_flyway_file" => "prerequisites/persistence-flyway.json",
    "market_data_projection_flyway_file" =>
      "prerequisites/market-data-projection-flyway.json",
    "query_flyway_file" => "prerequisites/query-service-flyway.json",
    "quickfix_gateway_flyway_file" => "prerequisites/quickfix-gateway-flyway.json",
    "connect_configs_topic_file" => "prerequisites/simplematch-connect-configs.txt",
    "connect_offsets_topic_file" => "prerequisites/simplematch-connect-offsets.txt",
    "connect_status_topic_file" => "prerequisites/simplematch-connect-status.txt"
  }.freeze

  REQUIRED_FILES = %w[
    status_before_file status_after_file pods_before_file pods_after_file
    target_before_file target_after_file status_pre_delete_file pods_pre_delete_file
    target_pre_delete_file pod_pre_delete_file pod_patch_file
    pod_delete_precondition_file target_delete_observation_file worker_loss_file
    provenance_file verifier_contract_file verifier_contract_script_file
    verifier_observer_script_file transition_file baseline_file probe_file
    kafka_baseline_file publication_evidence_file
    nodes_file control_plane_readyz_file control_plane_before_file
    control_plane_after_file control_plane_events_file connect_deployment_file
    connect_pdb_file connect_config_file image_cache_preflight_file
    account_connector_file risk_connector_file postgres_file
    topic_provisioning_file account_flyway_file risk_flyway_file
    persistence_flyway_file market_data_projection_flyway_file
    query_flyway_file quickfix_gateway_flyway_file connect_configs_topic_file
    connect_offsets_topic_file connect_status_topic_file
  ].freeze

  def self.load(path)
    report_path = safe_report_path(path)
    report = parse_json(report_path, "worker-loss report")
    evidence = WorkerLossChecks.object(report["evidence"], "evidence")
    paths = REQUIRED_FILES.to_h do |key|
      relative = WorkerLossChecks.string(evidence[key], "evidence.#{key}")
      candidate = safe_evidence_path(report_path.dirname, relative, key)
      [key, candidate]
    end
    new(report_path, report, paths).freeze
  end

  def self.load_prerequisites(directory)
    root = safe_relative_path(Pathname.new(directory),
                              "prerequisite evidence directory")
    WorkerLossChecks.assert(root.directory? && !root.symlink?,
                            "prerequisite evidence directory is missing or invalid")
    paths = PREREQUISITE_FILES.to_h do |key, relative|
      candidate = safe_relative_path(root.join(relative),
                                     "prerequisite evidence path #{key}")
      WorkerLossChecks.assert(candidate.file? && !candidate.symlink?,
                              "prerequisite evidence file is missing: #{relative}")
      [key, candidate]
    end
    new(root.join("prerequisite-evidence.json"), {"evidence" => {}}, paths).freeze
  end

  def self.safe_report_path(path)
    candidate = safe_relative_path(Pathname.new(path), "worker-loss report path")
    WorkerLossChecks.assert(candidate.file? && !candidate.symlink?,
                            "worker-loss report is missing or invalid: #{path}")
    candidate
  end

  def self.safe_evidence_path(root, relative, key)
    resolved = safe_relative_path(root.join(relative),
                                  "report evidence path #{key}")
    WorkerLossChecks.assert(resolved.file? && !resolved.symlink?,
                            "report evidence file is missing: #{resolved}")
    resolved
  end

  def self.safe_relative_path(path, label)
    WorkerLossChecks.assert(!path.absolute? && !path.each_filename.include?(".."),
                            "#{label} must be relative and local")
    current = Pathname.new(".")
    path.each_filename do |component|
      current = current.join(component)
      WorkerLossChecks.assert(!current.symlink?,
                              "#{label} contains a symlinked path component")
    end
    path
  end

  def self.parse_json(path, label)
    JSON.parse(path.read)
  rescue JSON::ParserError => error
    raise InvalidWorkerLossEvidence, "#{label} is not valid JSON: #{error.message}"
  end

  def json(key)
    self.class.parse_json(paths.fetch(key), key)
  end

  def text(key)
    paths.fetch(key).read
  end
end

class WorkerLossEnvelopeVerifier
  include WorkerLossChecks

  def verify(report)
    assert(report["schema_version"] == 2, "worker-loss report schema is not version 2")
    assert(report["profile"] == "connect-worker-loss", "worker-loss report profile is invalid")
    assert(%w[PASSED FAILED UNSUPPORTED].include?(report["status"]),
           "worker-loss report status is invalid")
    %w[cluster context namespace namespace_run_id run_id].each do |key|
      string(report[key], key)
    end
    assert(report["fault_mode"] == "pod-delete", "worker-loss fault mode is invalid")
    integer(report["deadline_seconds"], "deadline_seconds", minimum: 1, maximum: 900)
    integer(report["recovery_deadline_started_at_unix_ms"],
            "recovery_deadline_started_at_unix_ms", minimum: 0)
    boundaries = array(report["claim_boundary"], "claim_boundary")
    assert(!boundaries.empty?, "claim_boundary must not be empty")
    boundaries.each { |value| string(value, "claim_boundary entry") }
    reason = report["failure_reason"]
    assert(reason.nil? || reason.is_a?(String), "failure_reason has an invalid type")
    if report["status"] == "PASSED"
      assert(reason.nil? || reason.empty?, "a passed report cannot have a failure reason")
    else
      string(reason, "failure_reason")
    end
  end
end

class WorkerLossPrerequisiteVerifier
  include WorkerLossChecks

  JOB_KEYS = %w[
    topic_provisioning_file account_flyway_file risk_flyway_file
    persistence_flyway_file market_data_projection_flyway_file
    query_flyway_file quickfix_gateway_flyway_file
  ].freeze

  TOPIC_KEYS = %w[
    connect_configs_topic_file connect_offsets_topic_file connect_status_topic_file
  ].freeze

  JOB_NAMES = {
    "topic_provisioning_file" => "kafka-topic-provisioning",
    "account_flyway_file" => "account-service-flyway",
    "risk_flyway_file" => "risk-service-flyway",
    "persistence_flyway_file" => "persistence-flyway",
    "market_data_projection_flyway_file" => "market-data-projection-flyway",
    "query_flyway_file" => "query-service-flyway",
    "quickfix_gateway_flyway_file" => "quickfix-gateway-flyway"
  }.freeze

  CONTROL_PLANE_ROLES = %w[etcd kube-controller-manager kube-scheduler].freeze

  def verify(bundle)
    verify_distinct_paths(bundle, %w[control_plane_before_file control_plane_after_file],
                          "control-plane snapshots")
    nodes = bundle.json("nodes_file")
    verify_nodes(nodes)
    verify_control_plane(bundle)
    deployment = bundle.json("connect_deployment_file")
    verify_deployment(deployment, bundle)
    verify_pdb(bundle.json("connect_pdb_file"))
    verify_connect_config(bundle.json("connect_config_file"))
    verify_connect_pods(bundle.json("pods_before_file"), nodes)
    verify_postgres(bundle.json("postgres_file"))
    verify_connector(bundle.json("account_connector_file"),
                     "account-service-outbox", "account_service.outbox")
    verify_connector(bundle.json("risk_connector_file"),
                     "risk-service-outbox", "risk_service.outbox")
    JOB_KEYS.each { |key| verify_job(bundle.json(key), key) }
    TOPIC_KEYS.each { |key| verify_topic(bundle.text(key), key) }
  end

  private

  def verify_distinct_paths(bundle, keys, label)
    paths = keys.map { |key| bundle.paths.fetch(key) }
    assert(paths.uniq.length == paths.length, "#{label} must use distinct evidence files")
  end

  def ready?(object)
    object.fetch("status", {}).fetch("conditions", []).any? do |condition|
      condition["type"] == "Ready" && condition["status"] == "True"
    end
  end

  def verify_nodes(document)
    nodes = array(document["items"], "node evidence")
    ready = nodes.select do |node|
      node.dig("metadata", "labels", "simplematch.io/node-pool") == "local-resilience" &&
        ready?(node)
    end
    assert(ready.length >= 2,
           "prerequisite node evidence has fewer than two Ready resilience workers")
  end

  def verify_control_plane(bundle)
    assert(bundle.text("control_plane_readyz_file").include?("readyz check passed"),
           "control-plane readyz evidence is not successful")
    before = array(bundle.json("control_plane_before_file"), "control-plane before")
    after = array(bundle.json("control_plane_after_file"), "control-plane after")
    [before, after].each do |snapshot|
      verify_control_plane_snapshot(snapshot)
    end
    assert(before == after, "control-plane restart/readiness snapshot changed during the gate")
    events = object(bundle.json("control_plane_events_file"), "control-plane events")
    array(events["items"], "control-plane events")
  end

  def verify_control_plane_snapshot(snapshot)
    assert(snapshot.length == CONTROL_PLANE_ROLES.length && snapshot.all? do |entry|
      entry["phase"] == "Running" && entry["ready"] == true
    end, "control-plane snapshot is not fully Ready")
    identities = snapshot.map do |entry|
      match = entry["name"].to_s.match(/\A(etcd|kube-controller-manager|kube-scheduler)-(.+)-control-plane\z/)
      [match && match[1], match && match[2]]
    end
    assert(identities.map(&:first).sort == CONTROL_PLANE_ROLES.sort &&
           identities.map(&:last).compact.uniq.length == 1,
           "control-plane snapshot does not identify the canonical components")
  end

  def verify_deployment(document, bundle)
    spec = object(document["spec"], "Connect Deployment spec")
    template = object(spec.dig("template", "spec"), "Connect Deployment template")
    containers = array(template["containers"], "Connect containers")
    connect = containers.select { |container| container["name"] == "kafka-connect" }
    assert(document.dig("metadata", "name") == "kafka-connect" &&
           spec["replicas"] == 2 && connect.length == 1 &&
           connect.first["image"] == "quay.io/debezium/connect:3.6.0.Final" &&
           (template["volumes"] || []).all? { |volume| volume["persistentVolumeClaim"].nil? },
           "Connect Deployment prerequisite evidence is invalid")
    env = connect.first.fetch("env", []).to_h { |entry| [entry["name"], entry["value"]] }
    expected_env = {
      "GROUP_ID" => "simplematch-connect-local",
      "CONFIG_STORAGE_TOPIC" => "simplematch-connect-configs",
      "OFFSET_STORAGE_TOPIC" => "simplematch-connect-offsets",
      "STATUS_STORAGE_TOPIC" => "simplematch-connect-status",
      "CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR" => "3",
      "CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR" => "3",
      "CONNECT_STATUS_STORAGE_REPLICATION_FACTOR" => "3",
      "CONNECT_CONFIG_PROVIDERS" => "envvarprovider",
      "CONNECT_CONFIG_PROVIDERS_ENVVARPROVIDER_CLASS" =>
        "org.apache.kafka.common.config.provider.EnvVarConfigProvider"
    }
    assert(expected_env.all? { |key, value| env[key] == value },
           "Connect Deployment storage or provider configuration is invalid")
    assert(template.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience",
           "Connect Deployment node selector is invalid")
    spreads = template["topologySpreadConstraints"] || []
    assert(spreads.any? do |spread|
      spread["maxSkew"] == 1 && spread["topologyKey"] == "simplematch.io/worker-slot" &&
        spread["whenUnsatisfiable"] == "DoNotSchedule" &&
        spread.dig("labelSelector", "matchLabels", "app.kubernetes.io/name") == "kafka-connect" &&
        spread.dig("labelSelector", "matchLabels", "app.kubernetes.io/component") == "connector"
    end, "Connect Deployment spread policy is invalid")
    tolerations = template["tolerations"] || []
    required_tolerations = [
      ["simplematch.io/portable-workload", "Exists", "NoExecute", 30],
      ["node.kubernetes.io/not-ready", "Exists", "NoExecute", 30],
      ["node.kubernetes.io/unreachable", "Exists", "NoExecute", 30]
    ]
    assert(required_tolerations.all? do |key, operator, effect, seconds|
      tolerations.any? do |entry|
        entry["key"] == key && entry["operator"] == operator &&
          entry["effect"] == effect && entry["tolerationSeconds"] == seconds
      end
    end, "Connect Deployment tolerations are invalid")
    verify_image_cache(bundle.json("image_cache_preflight_file"), connect.first["image"])
  end

  def verify_image_cache(document, image)
    assert(document["schema_version"] == 1 && document["status"] == "PASS" &&
           document["image_reference"] == image &&
           document["image_identity"].is_a?(String) &&
           document["image_identity"].match?(IMAGE_ID),
           "image-cache preflight evidence is invalid")
    budget = integer(document["budget_seconds"], "image-cache budget", minimum: 1, maximum: 120)
    assert(budget >= 1, "image-cache budget is invalid")
    assert(document["failure_reason"].nil? || document["failure_reason"] == "",
           "image-cache PASS evidence has a failure reason")
    nodes = array(document["nodes"], "image-cache nodes")
    assert(nodes.length >= 2 && nodes.map { |entry| entry["node"] }.uniq.length == nodes.length,
           "image-cache preflight must cover distinct workers")
    assert(nodes.all? do |entry|
      entry["node"].is_a?(String) && !entry["node"].empty? &&
        entry["status"] == "PASS" && entry["inspect_status"] == "PASS" &&
        entry["execution_probe_status"] == "PASS" && entry["identity"].to_s.match?(IMAGE_ID)
    end && nodes.map { |entry| entry["identity"] }.uniq == [document["image_identity"]],
           "image-cache preflight identities are inconsistent")
  end

  def verify_pdb(document)
    assert(document.dig("metadata", "name") == "kafka-connect" &&
           document.dig("spec", "minAvailable") == 1 &&
           document.dig("spec", "selector", "matchLabels", "app.kubernetes.io/name") == "kafka-connect" &&
           document.dig("spec", "selector", "matchLabels", "app.kubernetes.io/component") == "connector",
           "Connect PDB prerequisite evidence is invalid")
  end

  def verify_connect_config(document)
    assert(document["metadata"]["name"] == "simplematch-kafka-connect-config" &&
           document.dig("data", "bootstrap_servers") == "kafka:9092" &&
           document.dig("data", "postgres_hostname") == "postgres" &&
           document.dig("data", "postgres_port") == "5432" &&
           document.dig("data", "postgres_dbname") == "simplematch" &&
           document.dig("data", "postgres_sslmode") == "disable" &&
           document.dig("data", "postgres_sslrootcert") == "/dev/null",
           "Kafka Connect profile ConfigMap evidence is invalid")
  end

  def verify_connect_pods(document, nodes_document)
    pods = array(document["items"], "Connect Pods")
    ready = pods.select { |pod| ready?(pod) && pod.dig("metadata", "deletionTimestamp").nil? }
    assert(ready.length == 2 && ready.map { |pod| pod.dig("spec", "nodeName") }.uniq.length == 2,
           "Connect worker Pods are not two Ready workers on distinct nodes")
    node_names = array(nodes_document["items"], "node evidence").filter_map do |node|
      next unless node.dig("metadata", "labels", "simplematch.io/node-pool") == "local-resilience"
      slot = node.dig("metadata", "labels", "simplematch.io/worker-slot")
      [node.dig("metadata", "name"), slot] if slot.to_s.match?(/\A[0-9]+\z/)
    end.to_h
    assert(ready.all? do |pod|
      node_names.key?(pod.dig("spec", "nodeName")) &&
        pod.dig("metadata", "labels", "app.kubernetes.io/name") == "kafka-connect" &&
        pod.dig("metadata", "labels", "app.kubernetes.io/component") == "connector" &&
        (pod.dig("spec", "volumes") || []).all? { |volume| volume["persistentVolumeClaim"].nil? }
    end, "Connect worker Pods are not bound to labelled, PVC-free resilience nodes")
  end

  def verify_postgres(document)
    assert(document["kind"] == "StatefulSet" && document.dig("metadata", "name") == "postgres" &&
           (document.dig("status", "readyReplicas") || 0) >= (document.dig("spec", "replicas") || 1),
           "PostgreSQL prerequisite evidence is invalid")
  end

  def verify_connector(document, connector, table)
    config = JSON.parse(document.dig("data", "connector.json"))
    placement = config.dig("config", "transforms.outbox.table.fields.additional.placement").to_s
    placements = placement.split(",").map(&:strip)
    assert(document.dig("metadata", "name") == "#{connector}-connector" &&
           config["name"] == connector && config.dig("config", "table.include.list") == table &&
           placements.count("headers_json:header:headers_json") == 1 &&
           placements.count("payload_type:header:eventType") == 1 &&
           !placements.include?(""),
           "#{connector} connector prerequisite evidence is invalid")
  rescue JSON::ParserError, TypeError => error
    raise InvalidWorkerLossEvidence,
          "#{connector} connector prerequisite evidence is not valid JSON: #{error.message}"
  end

  def verify_job(document, key)
    assert(document["kind"] == "Job" && document.dig("metadata", "name") == JOB_NAMES.fetch(key) &&
           document.fetch("status", {}).fetch("conditions", []).any? do |condition|
             condition["type"] == "Complete" && condition["status"] == "True"
           end, "Kubernetes prerequisite Job evidence is incomplete: #{key}")
  end

  def verify_topic(text, key)
    expected_topic = {
      "connect_configs_topic_file" => "simplematch-connect-configs",
      "connect_offsets_topic_file" => "simplematch-connect-offsets",
      "connect_status_topic_file" => "simplematch-connect-status"
    }.fetch(key)
    summary = text.lines.map(&:strip).find { |line| line.start_with?("Topic:") }
    summary_pattern = /\ATopic:\s+(\S+)\s+TopicId:\s+(\S+)\s+PartitionCount:\s+(\d+)\s+ReplicationFactor:\s+(\d+)\s+Configs:\s+(.+)\z/
    match = summary&.match(summary_pattern)
    assert(!match.nil? && match[1] == expected_topic && match[3].to_i.positive? &&
           match[4].to_i == 3,
           "Kafka topic summary is not exact: #{key}")
    configs = match[5].split(",").map { |entry| entry.strip }
    assert(configs.count { |entry| entry.start_with?("min.insync.replicas=") } == 1 &&
           configs.include?("min.insync.replicas=2"),
           "Kafka topic ISR configuration is not exact: #{key}")
  end
end

class WorkerLossRecoveryVerifier
  include WorkerLossChecks

  def verify(bundle)
    report = bundle.report
    assert(report["status"] == "PASSED", "worker-loss report is not passed")
    before_status = bundle.json("status_before_file")
    after_status = bundle.json("status_after_file")
    pre_status = bundle.json("status_pre_delete_file")
    before_pods = bundle.json("pods_before_file")
    after_pods = bundle.json("pods_after_file")
    pre_pods = bundle.json("pods_pre_delete_file")
    before = bundle.json("target_before_file")
    after = bundle.json("target_after_file")
    pre_target = bundle.json("target_pre_delete_file")

    verify_distinct_paths(bundle, %w[status_before_file status_after_file status_pre_delete_file],
                          "Connect status snapshots")
    verify_distinct_paths(bundle, %w[pods_before_file pods_after_file pods_pre_delete_file],
                          "Connect Pod snapshots")
    verify_distinct_paths(bundle, %w[target_before_file target_after_file target_pre_delete_file],
                          "task owner snapshots")
    [before_status, after_status, pre_status].each { |status| verify_status(status) }
    [before_pods, after_pods, pre_pods].each { |pods| verify_pods(pods) }
    verify_target(before, before_status, before_pods)
    verify_target(after, after_status, after_pods)
    verify_target(pre_target, pre_status, pre_pods)
    identity_keys = %w[task_id worker_id pod pod_uid worker_host pod_ip]
    assert(identity_keys.all? { |key| pre_target[key] == before[key] },
           "pre-delete task owner evidence changed before deletion")
    assert(before["task_id"] == after["task_id"], "Connect task id changed across worker loss")
    %w[worker_id pod_uid].each do |key|
      assert(before[key] != after[key], "Connect reassignment did not change #{key}")
    end
    verify_deletion(bundle, before, after_pods, report)
    verify_report_links(report, before, after)
  end

  private

  def verify_distinct_paths(bundle, keys, label)
    paths = keys.map { |key| bundle.paths.fetch(key) }
    assert(paths.uniq.length == paths.length, "#{label} must use distinct evidence files")
  end

  def verify_status(status)
    connector = object(status["connector"], "connector status")
    tasks = array(status["tasks"], "connector tasks")
    assert(status["name"] == "account-service-outbox" &&
           connector["state"] == "RUNNING" && tasks.length == 1,
           "Connect status must contain one RUNNING task")
    task = object(tasks.first, "connector task")
    integer(task["id"], "task id", minimum: 0)
    assert(task["state"] == "RUNNING", "Connect task is not RUNNING")
    string(task["worker_id"], "task worker_id")
  end

  def ready_pod?(pod)
    pod.dig("metadata", "deletionTimestamp").nil? &&
      pod.fetch("status", {}).fetch("conditions", []).any? do |condition|
        condition["type"] == "Ready" && condition["status"] == "True"
      end
  end

  def verify_pods(document)
    pods = array(document["items"], "Connect Pods")
    ready = pods.select { |pod| ready_pod?(pod) }
    assert(ready.length == 2,
           "Connect must have exactly two Ready, non-terminating Pods")
    assert(ready.map { |pod| pod.dig("spec", "nodeName") }.uniq.length == 2,
           "Connect Pods must be on distinct nodes")
    assert(ready.map { |pod| pod.dig("status", "podIP") }.uniq.length == 2,
           "Connect Pods must have distinct IP addresses")
    ready.each do |pod|
      labels = pod.dig("metadata", "labels") || {}
      assert(labels["app.kubernetes.io/name"] == "kafka-connect" &&
             labels["app.kubernetes.io/component"] == "connector",
             "Connect Pod labels are invalid")
      volumes = pod.dig("spec", "volumes") || []
      assert(volumes.none? { |volume| volume.key?("persistentVolumeClaim") },
             "Connect Pod unexpectedly uses a PVC")
      %w[name uid].each { |key| string(pod.dig("metadata", key), "Pod #{key}") }
      string(pod.dig("spec", "nodeName"), "Pod nodeName")
      string(pod.dig("status", "podIP"), "Pod podIP")
    end
  end

  def verify_target(target, status, pods)
    task = status.fetch("tasks").first
    %w[worker_id pod pod_uid node pod_ip worker_host].each do |key|
      string(target[key], "target.#{key}")
    end
    integer(target["task_id"], "target.task_id", minimum: 0)
    assert(target["ready"] == true, "target owner is not Ready")
    assert(target["task_id"] == task["id"] && target["worker_id"] == task["worker_id"],
           "target identity disagrees with Connect status")
    worker_host = target["worker_id"].sub(/:[0-9]+\z/, "")
    assert(target["worker_host"] == worker_host && target["pod_ip"] == worker_host,
           "target worker host does not identify its Pod IP")
    pod = pods.fetch("items").find { |item| item.dig("metadata", "name") == target["pod"] }
    assert(!pod.nil? && pod.dig("metadata", "uid") == target["pod_uid"] &&
           pod.dig("spec", "nodeName") == target["node"] &&
           pod.dig("status", "podIP") == target["pod_ip"] && ready_pod?(pod),
           "target identity disagrees with Pod evidence")
  end

  def verify_deletion(bundle, before, after_pods, report)
    pre_pod = bundle.json("pod_pre_delete_file")
    patch = bundle.json("pod_patch_file")
    precondition = bundle.json("pod_delete_precondition_file")
    observation = bundle.json("target_delete_observation_file")
    loss = bundle.json("worker_loss_file")
    [pre_pod, patch, precondition].each do |pod|
      assert(pod.dig("metadata", "name") == before["pod"] &&
             pod.dig("metadata", "uid") == before["pod_uid"],
             "Pod deletion evidence does not identify the task owner")
    end
    [patch, precondition].each do |pod|
      assert(pod.dig("metadata", "labels", "simplematch.io/worker-loss-run") == report["run_id"],
             "Pod deletion marker does not match the run")
    end
    assert(precondition.dig("metadata", "deletionTimestamp").nil?,
           "Pod was terminating before deletion")
    assert(ready_pod?(pre_pod), "task-owning Pod was not Ready before deletion")
    assert(observation["schema_version"] == 1 && observation["target_pod"] == before["pod"] &&
           observation["target_pod_uid"] == before["pod_uid"] &&
           observation["target_uid_absent"] == true &&
           %w[not-found replacement-pod].include?(observation["outcome"]) &&
           observation["observed_at_utc"].is_a?(String) &&
           !observation["observed_at_utc"].empty?,
           "Pod deletion observation does not prove the original UID disappeared")
    if observation["outcome"] == "replacement-pod"
      replacement = string(observation["replacement_pod_uid"], "replacement_pod_uid")
      assert(replacement != before["pod_uid"], "replacement retained the deleted Pod UID")
      replacement_pod = after_pods.fetch("items", []).find do |pod|
        pod.dig("metadata", "name") == before["pod"]
      end
      assert(!replacement_pod.nil? && replacement_pod.dig("metadata", "uid") == replacement,
             "replacement observation is not linked to the after-Pod snapshot")
    else
      assert(after_pods.fetch("items", []).none? do |pod|
        pod.dig("metadata", "name") == before["pod"] ||
          pod.dig("metadata", "uid") == before["pod_uid"]
      end, "after-reassignment Pods still contain the deleted task owner")
    end
    recovery_start = integer(report["recovery_deadline_started_at_unix_ms"],
                             "recovery deadline start", minimum: 1)
    requested_at = integer(loss["requested_at_unix_ms"], "fault request time", minimum: 1)
    expected_selector = "simplematch.io/worker-loss-run=#{report.fetch("run_id")}"
    assert(loss["fault"] == "pod-delete" && loss["target_pod"] == before["pod"] &&
           loss["target_pod_uid"] == before["pod_uid"] &&
           loss["delete_selector"] == expected_selector &&
           loss["delete_requested"] == true && loss["uid_precondition_test"] == true &&
           loss["pre_delete_recheck"] == true && loss["target_uid_absent"] == true &&
           loss.fetch("selector_target_count", 1) == 1 &&
           loss["delete_output_contains_target"] == true &&
           deletion_output_is_exact?(loss.fetch("delete_output", ""), before["pod"]) &&
           loss["recovery_deadline_started_at_unix_ms"] == recovery_start &&
           requested_at >= recovery_start,
           "worker-loss evidence does not prove an exact Pod deletion")
  end

  def deletion_output_is_exact?(output, pod_name)
    lines = output.lines.map(&:chomp)
    return false unless lines.length == 1

    escaped = Regexp.escape(pod_name)
    lines.first.match?(/\Apod\/#{escaped} deletion requested\z/) ||
      lines.first.match?(/\Apod\/#{escaped} deleted\z/) ||
      lines.first.match?(/\Apod "#{escaped}" deleted from .+ namespace\z/)
  end

  def verify_report_links(report, before, after)
    prerequisites = object(report["prerequisites"], "prerequisites")
    expected = {"connect_workers" => 2, "ready_workers_before" => 2,
                "ready_workers_after" => 2, "internal_topics_rf3" => true,
                "pdb_min_available_1" => true, "connect_has_no_pvc" => true,
                "service_owned_connectors" => true,
                "flyway_and_topic_prerequisites" => true}
    assert(expected.all? { |key, value| prerequisites[key] == value },
           "worker-loss prerequisites are not proven")
    reassignment = object(report["task_reassignment"], "task_reassignment")
    assert(reassignment["connector"] == "account-service-outbox" &&
           reassignment["before"] == before && reassignment["after"] == after &&
           reassignment["task_id"] == after["task_id"] &&
           reassignment["task_id_unchanged"] == true &&
           reassignment["worker_id_changed"] == (before["worker_id"] != after["worker_id"]) &&
           reassignment["pod_uid_changed"] == (before["pod_uid"] != after["pod_uid"]) &&
           reassignment["node_changed"] == (before["node"] != after["node"]),
           "worker-loss report does not prove task reassignment")
  end
end

class WorkerLossProvenanceVerifier
  include WorkerLossChecks

  def verify(bundle)
    provenance = bundle.json("provenance_file")
    report = bundle.report
    assert(provenance["status"] == "PASS" &&
           provenance["namespace"] == report["namespace"] &&
           provenance["namespace_run_id"] == report["namespace_run_id"],
           "provenance does not match report ownership")
    commit = string(provenance["current_commit"], "current_commit")
    assert(commit.match?(COMMIT), "provenance commit is invalid")
    runtime = string(provenance["cdc_runtime_signature"], "cdc_runtime_signature")
    retained_runtime = string(provenance["retained_cdc_runtime_signature"],
                              "retained_cdc_runtime_signature")
    verifier = string(provenance["cdc_verifier_signature"], "cdc_verifier_signature")
    retained_verifier = string(provenance["retained_cdc_verifier_signature"],
                               "retained_cdc_verifier_signature")
    assert([runtime, retained_runtime, verifier, retained_verifier].all? { |value| value.match?(SHA256) },
           "provenance signatures are invalid")
    assert(runtime == retained_runtime && provenance["runtime_reused"] == true,
           "provenance runtime identity drifted")
    assert([true, false].include?(provenance["verifier_signature_changed"]) &&
           provenance["verifier_signature_changed"] == (verifier != retained_verifier),
           "provenance verifier drift flag is invalid")
    image_identity = string(provenance["verifier_image_identity"], "verifier_image_identity")
    assert(image_identity.match?(IMAGE_ID),
           "provenance verifier image identity is invalid")
    verify_retained_authority(bundle, provenance, report, image_identity,
                              retained_runtime, retained_verifier)
    verify_retained_copy(bundle, provenance, "verifier_contract", "verifier_contract_script_file")
    verify_retained_copy(bundle, provenance, "verifier_observer", "verifier_observer_script_file")
    assert(bundle.text("verifier_contract_file").lines.map(&:chomp)
      .include?("CDC observer fixture header contract is valid."),
           "verifier contract evidence does not prove the fixture contract")
  end

  private

  def verify_retained_authority(bundle, provenance, report, image_identity,
                                retained_runtime, retained_verifier)
    relative_dir = string(provenance["retained_evidence_dir"], "retained_evidence_dir")
    authority = EvidenceBundle.safe_relative_path(Pathname.new(relative_dir),
                                                  "retained evidence authority")
    assert(authority.directory? && !authority.symlink?,
           "retained evidence authority is missing or not local")
    context_path = authority.join("run-context")
    assert(context_path.file? && !context_path.symlink?,
           "retained run context is missing or symlinked")
    context = {}
    context_path.each_line do |line|
      key, value = line.chomp.split("=", 2)
      assert(key && value && !context.key?(key),
             "retained run context is malformed")
      context[key] = value
    end
    assert(context["run_id"] == report["namespace_run_id"] &&
           context["namespace"] == report["namespace"] &&
           context["cluster"] == report["cluster"] &&
           context["cdc_runtime_signature"] == retained_runtime &&
           context["cdc_verifier_signature"] == retained_verifier,
           "retained run context does not match worker-loss provenance")
    source_revision = authority.join("source-revision")
    image_identity_file = authority.join("verifier-image-identity")
    retained_namespace = authority.join("retained-namespace")
    assert(source_revision.file? && !source_revision.symlink? &&
           source_revision.read.strip.match?(COMMIT),
           "retained source revision is missing or malformed")
    assert(image_identity_file.file? && !image_identity_file.symlink? &&
           image_identity_file.read.strip == image_identity,
           "retained verifier image identity does not match provenance")
    assert(retained_namespace.file? && !retained_namespace.symlink? &&
           retained_namespace.read.strip == report["namespace"],
           "retained namespace authority does not match the report")
  rescue SystemCallError => error
    raise InvalidWorkerLossEvidence, "retained evidence authority cannot be read: #{error.message}"
  end

  def verify_retained_copy(bundle, provenance, prefix, report_key)
    evidence_key = "#{prefix}_evidence_file"
    digest_key = "#{prefix}_sha256"
    string(provenance["#{prefix}_path"], "#{prefix}_path")
    assert(provenance[evidence_key] == bundle.report.dig("evidence", report_key),
           "#{prefix} retained copy is not linked from the report")
    digest = string(provenance[digest_key], digest_key)
    assert(digest.match?(SHA256) && Digest::SHA256.file(bundle.paths.fetch(report_key)).hexdigest == digest,
           "#{prefix} digest does not match its retained copy")
  end
end

class WorkerLossPublicationVerifier
  include WorkerLossChecks

  def verify(bundle)
    report = bundle.report
    publication = object(report["publication"], "publication")
    evidence = object(report["evidence"], "evidence")
    assert(%w[baseline_captured post_transition_probe exact_kafka_record
              transition_after_reassignment].all? { |key| publication[key] == true },
           "publication claims are incomplete")
    {"transition_file" => "transition_file", "baseline_file" => "baseline_file",
     "probe_file" => "probe_file", "kafka_baseline_file" => "kafka_baseline_file",
     "publication_evidence_file" => "publication_evidence_file"}.each do |claim, link|
      assert(publication[claim] == evidence[link], "publication #{claim} is not linked")
    end
    transition = bundle.json("transition_file")
    baseline = bundle.json("baseline_file")
    probe = bundle.json("probe_file")
    observed = bundle.json("publication_evidence_file")
    event_id = string(publication["event_id"], "publication.event_id")
    assert(event_id.match?(UUID), "publication event id is invalid")
    assert(transition["schema_version"] == 1 && transition["event_id"] == event_id &&
           transition["aggregate_id"] == baseline["aggregate_id"] &&
           transition["payload_type"] == probe["payload_type"] &&
           transition["transition"].is_a?(String) && !transition["transition"].empty?,
           "Account transition is not linked to its baseline and probe")
    created_at = integer(transition["transition_created_at_unix_ms"],
                         "transition_created_at_unix_ms", minimum: 0)
    reassigned_at = integer(transition["reassignment_observed_at_unix_ms"],
                            "reassignment_observed_at_unix_ms", minimum: 0)
    assert(created_at > reassigned_at && probe["created_at_unix_ms"] == created_at,
           "Account transition did not occur after reassignment")
    assert(probe["event_id"] == event_id &&
           probe["business_identity"] == transition["aggregate_id"],
           "outbox probe does not identify the Account transition")
    assert(baseline["schema_version"] == 1 && baseline["schema"] == "account_service" &&
           baseline["aggregate_type"] == "account_reservation" &&
           baseline["aggregate_id"] == transition["aggregate_id"],
           "outbox baseline identity is invalid")
    baseline_ids = array(baseline["event_ids"], "baseline.event_ids")
    assert(baseline_ids.uniq.length == baseline_ids.length &&
           baseline_ids.all? { |id| id.is_a?(String) && id.match?(UUID) } &&
           !baseline_ids.include?(event_id),
           "outbox baseline already contains the transition")
    expected_headers = Digest::SHA256.hexdigest(string(probe["headers_json"], "probe.headers_json"))
    assert(observed["event_id"] == event_id && observed["topic"] == probe["topic"] &&
           observed["expected_message_key"] == probe["message_key"] &&
           observed["expected_timestamp_unix_ms"] == probe["created_at_unix_ms"] &&
           observed["expected_headers_json_sha256"] == expected_headers &&
           observed["expected_event_type"] == probe["payload_type"] &&
           observed["expected_payload_sha256"] == probe["payload_sha256"],
           "Kafka publication evidence is not linked to the outbox probe")
    checks = object(observed["verification"], "publication.verification")
    assert(%w[headers_exact key_exact timestamp_exact payload_exact].all? { |key| checks[key] == true },
           "Kafka publication exactness checks are incomplete")
    verify_kafka_location(bundle.text("kafka_baseline_file"), observed)
  end

  private

  def verify_kafka_location(text, observed)
    partition = integer(observed["partition"], "publication.partition", minimum: 0)
    offset = integer(observed["offset"], "publication.offset", minimum: 0)
    lines = text.lines.map(&:chomp).reject(&:empty?)
    assert(!lines.empty?, "Kafka baseline is empty")
    baselines = lines.map do |line|
      fields = line.chomp.split("\t", -1)
      assert(fields.length == 2 && fields.all? { |field| field.match?(/\A[0-9]+\z/) },
             "Kafka baseline contains a malformed partition offset")
      fields.map(&:to_i)
    end
    assert(baselines.map(&:first).uniq.length == baselines.length,
           "Kafka baseline contains duplicate partitions")
    partitions = baselines.map(&:first).sort
    assert(partitions == (0...partitions.length).to_a,
           "Kafka baseline does not cover the complete partition set")
    assert(baselines.any? { |candidate_partition, candidate_offset|
      candidate_partition == partition && offset >= candidate_offset
    }, "Kafka publication location is not after its retained baseline")
  end
end

def verify_envelope(path)
  report_path = EvidenceBundle.safe_report_path(path)
  report = EvidenceBundle.parse_json(report_path, "worker-loss report")
  WorkerLossEnvelopeVerifier.new.verify(report)
end

def verify_passed(path)
  bundle = EvidenceBundle.load(path)
  WorkerLossEnvelopeVerifier.new.verify(bundle.report)
  WorkerLossPrerequisiteVerifier.new.verify(bundle)
  WorkerLossRecoveryVerifier.new.verify(bundle)
  WorkerLossProvenanceVerifier.new.verify(bundle)
  WorkerLossPublicationVerifier.new.verify(bundle)
end

def verify_prerequisites(path)
  bundle = EvidenceBundle.load_prerequisites(path)
  WorkerLossPrerequisiteVerifier.new.verify(bundle)
end

begin
  mode, path = ARGV
  raise InvalidWorkerLossEvidence, "usage: connect-worker-loss-evidence.rb MODE REPORT" unless path

  case mode
  when "envelope" then verify_envelope(path)
  when "passed" then verify_passed(path)
  when "prerequisites" then verify_prerequisites(path)
  else raise InvalidWorkerLossEvidence, "unknown verifier mode: #{mode}"
  end
rescue InvalidWorkerLossEvidence, JSON::ParserError, KeyError, TypeError,
       NoMethodError, SystemCallError => error
  warn "Connect worker-loss verifier: #{error.message}"
  exit 1
end

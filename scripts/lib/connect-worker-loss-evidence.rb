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

EvidenceBundle = Data.define(:report_path, :report, :paths) do
  REQUIRED_FILES = %w[
    status_before_file status_after_file pods_before_file pods_after_file
    target_before_file target_after_file status_pre_delete_file pods_pre_delete_file
    target_pre_delete_file pod_pre_delete_file pod_patch_file
    pod_delete_precondition_file target_delete_observation_file worker_loss_file
    provenance_file verifier_contract_file verifier_contract_script_file
    verifier_observer_script_file transition_file baseline_file probe_file
    kafka_baseline_file publication_evidence_file
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
    new(report_path, report, paths)
  end

  def self.safe_report_path(path)
    candidate = Pathname.new(path)
    WorkerLossChecks.assert(!candidate.absolute? && !candidate.each_filename.include?(".."),
                            "worker-loss report path must be relative and local")
    WorkerLossChecks.assert(candidate.file? && !candidate.symlink?,
                            "worker-loss report is missing or invalid: #{path}")
    candidate
  end

  def self.safe_evidence_path(root, relative, key)
    candidate = Pathname.new(relative)
    WorkerLossChecks.assert(!candidate.absolute? && !candidate.each_filename.include?(".."),
                            "report evidence path is not relative and local: #{key}")
    resolved = root.join(candidate)
    WorkerLossChecks.assert(resolved.file? && !resolved.symlink?,
                            "report evidence file is missing: #{resolved}")
    resolved
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

    [before_status, after_status, pre_status].each { |status| verify_status(status) }
    [before_pods, after_pods, pre_pods].each { |pods| verify_pods(pods) }
    verify_target(before, before_status, before_pods)
    verify_target(after, after_status, after_pods)
    verify_target(pre_target, pre_status, pre_pods)
    identity_keys = %w[task_id worker_id pod pod_uid worker_host pod_ip]
    assert(identity_keys.all? { |key| pre_target[key] == before[key] },
           "pre-delete task owner evidence changed before deletion")
    assert(before["task_id"] == after["task_id"], "Connect task id changed across worker loss")
    %w[worker_id pod pod_uid node].each do |key|
      assert(before[key] != after[key], "Connect reassignment did not change #{key}")
    end
    assert(after_pods.fetch("items", []).none? do |pod|
      pod.dig("metadata", "name") == before["pod"] ||
        pod.dig("metadata", "uid") == before["pod_uid"]
    end, "after-reassignment Pods still contain the deleted task owner")
    verify_deletion(bundle, before, report)
    verify_report_links(report, before, after)
  end

  private

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
    assert(pods.length == 2 && pods.all? { |pod| ready_pod?(pod) },
           "Connect must have exactly two Ready Pods")
    assert(pods.map { |pod| pod.dig("spec", "nodeName") }.uniq.length == 2,
           "Connect Pods must be on distinct nodes")
    assert(pods.map { |pod| pod.dig("status", "podIP") }.uniq.length == 2,
           "Connect Pods must have distinct IP addresses")
    pods.each do |pod|
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
           pod.dig("status", "podIP") == target["pod_ip"],
           "target identity disagrees with Pod evidence")
  end

  def verify_deletion(bundle, before, report)
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
           loss["delete_output_contains_target"] == true &&
           loss.fetch("delete_output", "").include?(before["pod"]) &&
           loss["recovery_deadline_started_at_unix_ms"] == recovery_start &&
           requested_at >= recovery_start,
           "worker-loss evidence does not prove an exact Pod deletion")
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
           %w[task_id_unchanged worker_id_changed pod_uid_changed node_changed].all? do |key|
             reassignment[key] == true
           end, "worker-loss report does not prove task reassignment")
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
    verify_retained_copy(bundle, provenance, "verifier_contract", "verifier_contract_script_file")
    verify_retained_copy(bundle, provenance, "verifier_observer", "verifier_observer_script_file")
    assert(bundle.text("verifier_contract_file").lines.map(&:chomp)
      .include?("CDC observer fixture header contract is valid."),
           "verifier contract evidence does not prove the fixture contract")
  end

  private

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
    baselines = text.lines.map do |line|
      fields = line.chomp.split("\t", -1)
      next unless fields.length == 2 && fields.all? { |field| field.match?(/\A[0-9]+\z/) }
      fields.map(&:to_i)
    end.compact
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
  WorkerLossRecoveryVerifier.new.verify(bundle)
  WorkerLossProvenanceVerifier.new.verify(bundle)
  WorkerLossPublicationVerifier.new.verify(bundle)
end

begin
  mode, path = ARGV
  raise InvalidWorkerLossEvidence, "usage: connect-worker-loss-evidence.rb MODE REPORT" unless path

  case mode
  when "envelope" then verify_envelope(path)
  when "passed" then verify_passed(path)
  else raise InvalidWorkerLossEvidence, "unknown verifier mode: #{mode}"
  end
rescue InvalidWorkerLossEvidence, KeyError, TypeError, NoMethodError => error
  warn "Connect worker-loss verifier: #{error.message}"
  exit 1
end

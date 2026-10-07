#!/usr/bin/env ruby
require "json"
require "digest"
require_relative "resting-buy-verification"

# Verifies one same-owner FIX recovery from redacted deployment observations.
module GatewayRecoveryVerification
  InvalidEvidence = RestingBuyVerification::InvalidEvidence

  def self.equal(expected, actual, field)
    RestingBuyVerification.equal(expected, actual, "Gateway recovery #{field}")
  end

  # Raw Kubernetes resources remain private; only these approved identities escape.
  def self.observe_owner(resources)
    pod, service, claim, volume = resources.values_at("pod", "service", "pvc", "pv")
    selector = service.fetch("spec").fetch("selector")
    labels = pod.fetch("metadata").fetch("labels")
    equal(true, selector.all? { |key, value| labels[key] == value }, "Service selector")
    owner = pod.fetch("metadata").fetch("name")
    container = pod.fetch("spec").fetch("containers").find { |item| item["name"] == "quickfix-gateway" }
    identity = container.fetch("env").find { |item| item["name"] == "SIMPLEMATCH_QUICKFIX_GATEWAY_OWNER_ID" }
    equal("metadata.name", identity.fetch("valueFrom").fetch("fieldRef").fetch("fieldPath"), "runtime owner source")
    mount = pod.fetch("spec").fetch("volumes").find { |item| item["name"] == "quickfix-data" }
    equal(claim.fetch("metadata").fetch("name"), mount.fetch("persistentVolumeClaim").fetch("claimName"), "mounted claim")
    equal(volume.fetch("metadata").fetch("name"), claim.fetch("spec").fetch("volumeName"), "bound volume")
    equal(claim.fetch("metadata").fetch("uid"), volume.fetch("spec").fetch("claimRef").fetch("uid"), "volume claim UID")
    [claim, volume].each { |item| equal("Bound", item.fetch("status").fetch("phase"), "storage binding") }
    {
      "podName" => owner, "podUid" => pod.fetch("metadata").fetch("uid"),
      "nodeName" => pod.fetch("spec").fetch("nodeName"),
      "serviceName" => service.fetch("metadata").fetch("name"), "serviceUid" => service.fetch("metadata").fetch("uid"),
      "serviceOwner" => selector.fetch("statefulset.kubernetes.io/pod-name"),
      "ready" => pod.fetch("status").fetch("conditions").any? { |item| item["type"] == "Ready" && item["status"] == "True" },
      "pvcName" => claim.fetch("metadata").fetch("name"), "pvcUid" => claim.fetch("metadata").fetch("uid"),
      "pvName" => volume.fetch("metadata").fetch("name"), "pvUid" => volume.fetch("metadata").fetch("uid")
    }
  rescue KeyError, NoMethodError, TypeError => failure
    raise InvalidEvidence, "invalid owner resources: #{failure.class}"
  end

  def self.observe_sample(pods, original_uid)
    items = pods.fetch("items")
    original = items.find { |pod| pod.fetch("metadata").fetch("uid") == original_uid }
    running = lambda do |pod|
      pod.fetch("status").fetch("containerStatuses", []).any? do |container|
        container["name"] == "quickfix-gateway" && container.fetch("state").key?("running")
      end
    end
    {
      "observedAtEpochMs" => (Time.now.to_r * 1000).to_i,
      "podUids" => items.map { |pod| pod.fetch("metadata").fetch("uid") },
      "activeOwners" => items.count { |pod| running.call(pod) },
      "oldOwnerInterrupted" => original.nil? || original.fetch("metadata").key?("deletionTimestamp") || !running.call(original)
    }
  end

  def self.observe_wal(lines, account_id, cl_ord_id)
    records = lines.filter_map do |line|
      document = JSON.parse(line)
      next unless document["accountId"] == account_id && document["clOrdId"] == cl_ord_id
      {"recordId" => document.fetch("recordId"), "sha256" => Digest::SHA256.hexdigest(line.chomp)}
    end
    {"count" => records.size, "records" => records}
  end

  def self.verify_owner(evidence)
    before = evidence.fetch("before").fetch("owner")
    after = evidence.fetch("after").fetch("owner")
    fields = %w[podName nodeName serviceName serviceUid serviceOwner pvcName pvcUid pvName pvUid]
    fields.each do |field|
      equal(true, before.fetch(field).is_a?(String) && !before.fetch(field).empty?, field)
      equal(before.fetch(field), after.fetch(field), field)
    end
    equal("quickfix-gateway-0", before.fetch("podName"), "logical owner")
    equal(before.fetch("podName"), before.fetch("serviceOwner"), "Service target")
    equal(true, before.fetch("ready"), "baseline Ready")
    equal(true, after.fetch("ready"), "recovered Ready")
    [before, after].each { |value| equal(true, value.fetch("podUid").is_a?(String) && !value.fetch("podUid").empty?, "Pod UID") }
    equal(false, before.fetch("podUid") == after.fetch("podUid"), "Pod replacement")
    samples = evidence.fetch("samples")
    equal(true, samples.is_a?(Array) && !samples.empty?, "replacement samples")
    equal(true, samples.any? { |sample| sample.fetch("oldOwnerInterrupted") == true }, "observed interruption")
    samples.each do |sample|
      count = sample.fetch("activeOwners")
      equal(true, count.is_a?(Integer) && (0..1).cover?(count), "single active owner")
    end
    true
  rescue KeyError, ArgumentError, TypeError => failure
    raise InvalidEvidence, "missing or invalid owner evidence: #{failure.class}"
  end

  def self.verify_durable_state(evidence)
    before = evidence.fetch("before")
    after = evidence.fetch("after")
    old_session = before.fetch("session")
    session = after.fetch("session")
    [old_session, session].each { |value| equal(1, value.fetch("count"), "session count") }
    identity = old_session.fetch("identity")
    equal(true, identity.is_a?(Array) && identity.size == 8 && identity.all? { |value| value.is_a?(String) }, "session identity fields")
    equal("FIX.4.4", identity[0], "session protocol")
    equal(true, old_session.fetch("creationTime").is_a?(String) && !old_session.fetch("creationTime").empty?, "session creation time")
    %w[identity creationTime].each do |field|
      equal(old_session.fetch(field), session.fetch(field), "session #{field}")
    end
    %w[incomingSequence outgoingSequence].each do |field|
      previous = old_session.fetch(field)
      current = session.fetch(field)
      equal(true, previous.is_a?(Integer) && previous.positive? && current.is_a?(Integer) &&
        current > previous, "continuing #{field}")
    end
    messages = old_session.fetch("messages")
    equal(true, messages.is_a?(Array) && !messages.empty?, "retained messages")
    messages.each do |message|
      equal(true, message.fetch("sequence").is_a?(Integer) && message.fetch("sequence").positive?, "retained message sequence")
      equal(true, /\A[0-9a-f]{64}\z/.match?(message.fetch("sha256")), "retained message digest")
      equal(true, session.fetch("messages").include?(message), "retained message identity")
    end
    [before, after].each { |value| equal(1, value.fetch("wal").fetch("count"), "original WAL count") }
    equal(before.fetch("wal").fetch("records"), after.fetch("wal").fetch("records"), "original WAL record")
    equal(1, before.fetch("wal").fetch("records").size, "WAL evidence count")
    record = before.fetch("wal").fetch("records").first
    equal(true, record.fetch("recordId").is_a?(String) && !record.fetch("recordId").empty?, "WAL record identity")
    equal(true, /\A[0-9a-f]{64}\z/.match?(record.fetch("sha256")), "WAL record digest")
    true
  rescue KeyError, ArgumentError, TypeError => failure
    raise InvalidEvidence, "missing or invalid durable recovery evidence: #{failure.class}"
  end

  def self.verify_protocol(evidence)
    protocol = evidence.fetch("protocol")
    timing = evidence.fetch("timing")
    started = timing.fetch("startedAtEpochMs")
    completed = timing.fetch("completedAtEpochMs")
    budget = timing.fetch("budgetMillis")
    equal(true, [started, completed, budget].all? { |value| value.is_a?(Integer) && value.positive? }, "recovery clock")
    equal(true, completed > started && completed - started <= budget, "bounded recovery")
    %w[originalSequence resentSequence originalOrderSequence retryMessageSequence logonCount logoutCount].each do |field|
      value = protocol.fetch(field)
      equal(true, value.is_a?(Integer) && value.positive?, "protocol #{field}")
    end
    %w[sessionId originalExecId resentExecId originalSendingTime origSendingTime testRequestId heartbeatTestRequestId].each do |field|
      value = protocol.fetch(field)
      equal(true, value.is_a?(String) && !value.empty?, "protocol #{field}")
    end
    equal(true, /\A[0-9a-f]{64}\z/.match?(protocol.fetch("originalOrderBodySha256")), "original order body digest")
    equal(protocol.fetch("originalOrderBodySha256"), protocol.fetch("retryOrderBodySha256"), "retry body digest")
    equal(true, protocol.fetch("logonCount") >= 2 && protocol.fetch("logoutCount").positive?, "client reconnect")
    disconnected = protocol.fetch("logoutAtEpochMs")
    reconnected = protocol.fetch("reconnectedAtEpochMs")
    retried = protocol.fetch("retrySentAtEpochMs")
    equal(true, started <= disconnected && disconnected <= reconnected &&
      reconnected <= retried && retried <= completed, "observed reconnect ordering")
    equal(protocol.fetch("originalSequence"), protocol.fetch("resentSequence"), "resent sequence")
    equal(protocol.fetch("originalExecId"), protocol.fetch("resentExecId"), "resent ExecID")
    equal(protocol.fetch("originalSendingTime"), protocol.fetch("origSendingTime"), "resent original time")
    equal(true, protocol.fetch("possDup"), "FIX retransmission flag")
    equal(true, protocol.fetch("retryMessageSequence") > protocol.fetch("originalOrderSequence"), "retry sequence")
    equal(protocol.fetch("testRequestId"), protocol.fetch("heartbeatTestRequestId"), "retry session heartbeat")
    true
  rescue KeyError, ArgumentError, TypeError => failure
    raise InvalidEvidence, "missing or invalid protocol recovery evidence: #{failure.class}"
  end

  def self.verify(business, recovery)
    RestingBuyVerification.verify(business)
    verify_owner(recovery)
    verify_durable_state(recovery)
    verify_protocol(recovery)
    protocol = recovery.fetch("protocol")
    %w[accountId clOrdId].each do |field|
      equal(business.fetch("expected").fetch(field), protocol.fetch(field), "retry #{field}")
    end
    session = recovery.fetch("before").fetch("session").fetch("identity")
    equal("#{session[0]}:#{session[4]}->#{session[1]}", protocol.fetch("sessionId"), "reconnected session")
    %w[commandId orderId reservationId].each do |field|
      equal(business.fetch("risk").fetch(field), recovery.fetch("riskAfter").fetch(field), "stable Risk #{field}")
    end
    result = RestingBuyVerification.verify(business.merge(
      "risk" => recovery.fetch("riskAfter"), "durable" => recovery.fetch("durableAfter"),
      "open" => recovery.fetch("open"), "after" => recovery.fetch("final")))
    timing = recovery.fetch("timing")
    result.merge(
      "scenario" => "gateway-same-owner-recovery", "owner" => recovery.fetch("after").fetch("owner").fetch("podName"),
      "ownerSampleCount" => recovery.fetch("samples").size,
      "protocolRecoveryPassed" => true, "businessRecoveryPassed" => true, "infrastructureReady" => true,
      "recoveryMillis" => timing.fetch("completedAtEpochMs") - timing.fetch("startedAtEpochMs"),
      "recoveryBudgetMillis" => timing.fetch("budgetMillis"))
  rescue KeyError, ArgumentError, TypeError => failure
    raise InvalidEvidence, "missing or invalid Gateway recovery evidence: #{failure.class}"
  end

  def self.read_evidence(directory)
    read = lambda { |name| JSON.parse(File.read(File.join(directory, "recovery", name + ".json"))) }
    {
      "before" => %w[owner session wal].to_h { |name| [name, read.call("before-#{name}")] },
      "after" => %w[owner session wal].to_h { |name| [name, read.call("after-#{name}")] },
      "samples" => File.readlines(File.join(directory, "recovery/owner-samples.jsonl")).map { |line| JSON.parse(line) },
      "protocol" => read.call("protocol"), "timing" => read.call("timing"),
      "riskAfter" => read.call("risk-after"), "durableAfter" => read.call("durable-after"),
      "open" => read.call("gateway-open"), "final" => read.call("gateway-final")
    }
  end
end

if $PROGRAM_NAME == __FILE__
  operation, *values = ARGV
  begin
    result = case operation
    when "owner"
      resources = %w[pod service pvc pv].zip(values).to_h.transform_values { |path| JSON.parse(File.read(path)) }
      GatewayRecoveryVerification.observe_owner(resources)
    when "sample"
      GatewayRecoveryVerification.observe_sample(JSON.parse($stdin.read), values.fetch(0))
    when "wal"
      GatewayRecoveryVerification.observe_wal($stdin.each_line, *values)
    when "timing"
      {"startedAtEpochMs" => Integer(values.fetch(0)), "completedAtEpochMs" => (Time.now.to_r * 1000).to_i,
        "budgetMillis" => Integer(values.fetch(1))}
    when "verify"
      directory = values.fetch(0)
      verdict = GatewayRecoveryVerification.verify(RestingBuyVerification.read_evidence(directory), GatewayRecoveryVerification.read_evidence(directory))
      File.write(File.join(directory, "recovery-result.json"), JSON.pretty_generate(verdict) + "\n")
      verdict
    else
      abort "usage: gateway-recovery-verification.rb owner|sample|wal|timing|verify [values]"
    end
    puts JSON.pretty_generate(result) unless operation == "sample"
    puts JSON.generate(result) if operation == "sample"
  rescue GatewayRecoveryVerification::InvalidEvidence, KeyError, NoMethodError, TypeError, ArgumentError, Errno::ENOENT, JSON::ParserError => failure
    warn "Gateway recovery evidence rejected: #{failure.message}" if failure.is_a?(GatewayRecoveryVerification::InvalidEvidence)
    warn "Gateway recovery evidence unavailable: #{failure.class}" unless failure.is_a?(GatewayRecoveryVerification::InvalidEvidence)
    exit 1
  end
end

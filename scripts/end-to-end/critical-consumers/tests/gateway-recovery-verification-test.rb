#!/usr/bin/env ruby
require "json"
require "fileutils"
require "minitest/autorun"
require "open3"
require "tmpdir"
require_relative "../lib/gateway-recovery-verification"

class GatewayRecoveryVerificationTest < Minitest::Test
  def test_usage_lists_every_supported_operation
    command = File.join(__dir__, "../lib/gateway-recovery-verification.rb")
    output, error, status = Open3.capture3("ruby", command, "unsupported")
    refute status.success?
    assert_empty output
    assert_equal "usage: gateway-recovery-verification.rb owner|restoration|sample|wal|journal|timing|verify [values]\n", error
  end

  def fixture
    JSON.parse(File.read(File.join(__dir__, "fixtures/gateway-recovery.json")))
  end

  def owner_resources
    JSON.parse(File.read(File.join(__dir__, "fixtures/gateway-owner-resources.json")))
  end

  def test_observes_only_redacted_owner_identity_and_bound_storage
    resources = owner_resources
    result = GatewayRecoveryVerification.observe_owner(resources)
    assert_equal fixture.fetch("before").fetch("owner"), result
    refute_includes JSON.generate(result), "PRIVATE_TEST_TOKEN_NOT_FOR_EVIDENCE"
    resources["service"]["spec"]["selector"]["app.kubernetes.io/name"] = "another-application"
    assert_raises(GatewayRecoveryVerification::InvalidEvidence) do
      GatewayRecoveryVerification.observe_owner(resources)
    end
    resources = owner_resources
    resources["pv"]["spec"]["claimRef"]["uid"] = "another-claim"
    assert_raises(GatewayRecoveryVerification::InvalidEvidence) do
      GatewayRecoveryVerification.observe_owner(resources)
    end
  end

  def test_counts_running_terminating_owners_and_observes_actual_interruption
    original = owner_resources.fetch("pod")
    replacement = Marshal.load(Marshal.dump(original))
    replacement["metadata"]["uid"] = "new-pod"
    original["metadata"]["deletionTimestamp"] = "2026-10-07T08:01:00Z"
    sample = GatewayRecoveryVerification.observe_sample({"items" => [original, replacement]}, "old-pod")
    assert_equal 2, sample.fetch("activeOwners")
    assert_equal true, sample.fetch("oldOwnerInterrupted")
  end

  def test_requires_the_real_gateway_mount_and_volume_node_assignment
    resources = owner_resources
    resources["pod"]["spec"]["containers"][0]["volumeMounts"][0]["mountPath"] = "/another-mount"
    assert_raises(GatewayRecoveryVerification::InvalidEvidence) do
      GatewayRecoveryVerification.observe_owner(resources)
    end
    resources = owner_resources
    resources["pv"]["spec"]["nodeAffinity"]["required"]["nodeSelectorTerms"][0]["matchExpressions"][0]["values"] = ["another-worker"]
    assert_raises(GatewayRecoveryVerification::InvalidEvidence) do
      GatewayRecoveryVerification.observe_owner(resources)
    end
  end

  def test_checks_actual_post_restoration_readiness_without_claiming_trading_is_open
    resources = owner_resources
    result = GatewayRecoveryVerification.observe_restoration(resources)
    assert_equal true, result.fetch("gatewayReady")
    assert_equal true, result.fetch("operationsOverridesRemoved")
    assert_equal false, result.fetch("postRestorationOpenProven")
    resources["health"]["status"] = "DOWN"
    assert_raises(GatewayRecoveryVerification::InvalidEvidence) do
      GatewayRecoveryVerification.observe_restoration(resources)
    end
    resources = owner_resources
    resources["statefulset"]["spec"]["template"]["spec"]["containers"][0]["env"] <<
      {"name" => "SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_HTTP_ENABLED", "value" => "true"}
    assert_raises(GatewayRecoveryVerification::InvalidEvidence) do
      GatewayRecoveryVerification.observe_restoration(resources)
    end
  end

  def test_hashes_the_exact_original_wal_line_without_exposing_raw_fix
    original = JSON.generate({"recordId" => "original-record", "accountId" => "test-account", "clOrdId" => "test-order", "rawFix" => "PRIVATE_FIX_PAYLOAD"})
    observation = GatewayRecoveryVerification.observe_wal([original + "\n"], "test-account", "test-order")
    assert_equal 1, observation.fetch("count")
    assert_equal "original-record", observation.fetch("records").first.fetch("recordId")
    assert_equal "7406a66c96a3f1c25c1fb5d2ff037e7ba46640a58ab494b5a198a4beecdb19c5", observation.fetch("records").first.fetch("sha256")
    refute_includes JSON.generate(observation), "PRIVATE_FIX_PAYLOAD"
  end

  def test_accepts_one_replacement_with_the_original_logical_owner_and_storage
    assert_equal true, GatewayRecoveryVerification.verify_owner(fixture)
    {
      ["after", "owner", "podUid"] => "old-pod",
      ["after", "owner", "serviceOwner"] => "quickfix-gateway-1",
      ["after", "owner", "serviceUid"] => "another-service",
      ["after", "owner", "pvcUid"] => "another-claim",
      ["after", "owner", "pvUid"] => "another-volume",
      ["after", "owner", "nodeName"] => "worker-2",
      ["after", "owner", "ready"] => false
    }.each do |path, value|
      evidence = fixture
      evidence.dig(*path[0...-1])[path.last] = value
      assert_raises(GatewayRecoveryVerification::InvalidEvidence, path.join(".")) do
        GatewayRecoveryVerification.verify_owner(evidence)
      end
    end
    evidence = fixture
    evidence["samples"][1]["activeOwners"] = 2
    assert_raises(GatewayRecoveryVerification::InvalidEvidence) do
      GatewayRecoveryVerification.verify_owner(evidence)
    end
  end

  def test_requires_continuous_session_messages_and_the_original_wal_record
    assert_equal true, GatewayRecoveryVerification.verify_durable_state(fixture)
    {
      ["after", "session", "count"] => 2,
      ["after", "session", "creationTime"] => "2026-10-07T09:00:00",
      ["after", "session", "incomingSequence"] => 1,
      ["after", "session", "outgoingSequence"] => 1,
      ["after", "session", "messages"] => [],
      ["before", "session", "identity"] => [],
      ["after", "session", "identity"] => ["FIX.4.4", "ANOTHER-OWNER", "", "", "CLIENT", "", "", ""],
      ["before", "session", "messages"] => [{"sequence" => 2, "sha256" => "not-a-digest"}],
      ["before", "wal", "records"] => [{"recordId" => "", "sha256" => "not-a-digest"}],
      ["after", "wal", "count"] => 0,
      ["after", "wal", "records"] => []
    }.each do |path, value|
      evidence = fixture
      evidence.dig(*path[0...-1])[path.last] = value
      if path.first == "before"
        evidence.dig("after", *path[1...-1])[path.last] = value
      end
      assert_raises(GatewayRecoveryVerification::InvalidEvidence, path.join(".")) do
        GatewayRecoveryVerification.verify_durable_state(evidence)
      end
    end
  end

  def test_requires_the_original_recovery_journal_prefix_and_accepted_outcome
    {
      ["after", "journal", "states"] => ["ACCEPTED"],
      ["after", "journal", "recordId"] => "another-command",
      ["before", "journal", "states"] => []
    }.each do |path, value|
      evidence = fixture
      evidence.dig(*path[0...-1])[path.last] = value
      assert_raises(GatewayRecoveryVerification::InvalidEvidence, path.join(".")) do
        GatewayRecoveryVerification.verify_durable_state(evidence)
      end
    end
    observation = GatewayRecoveryVerification.observe_journal(
      ["original\tUNKNOWN\n", "unrelated\tREJECTED\n", "original\tACCEPTED\n"], "original")
    assert_equal({"recordId" => "original", "states" => ["UNKNOWN", "ACCEPTED"]}, observation)
  end

  def test_rejects_a_different_session_protocol_even_when_both_observations_match
    evidence = fixture
    %w[before after].each { |phase| evidence[phase]["session"]["identity"][0] = "FIX.4.2" }
    assert_raises(GatewayRecoveryVerification::InvalidEvidence) do
      GatewayRecoveryVerification.verify_durable_state(evidence)
    end
  end

  def test_requires_reconnect_resend_and_a_processed_retry_within_the_deadline
    assert_equal true, GatewayRecoveryVerification.verify_protocol(fixture)
    {
      "logonCount" => 1, "logoutCount" => 0, "logoutAtEpochMs" => 999999,
      "reconnectedAtEpochMs" => 999999, "resentSequence" => 3,
      "resentExecId" => "another-execution", "origSendingTime" => "another-time",
      "possDup" => false, "retryMessageSequence" => 2,
      "resendRequestSentAtEpochMs" => 999999, "resentReceivedAtEpochMs" => 1010050,
      "heartbeatTestRequestId" => "another-request"
    }.each do |field, value|
      evidence = fixture
      evidence["protocol"][field] = value
      assert_raises(GatewayRecoveryVerification::InvalidEvidence, field) do
        GatewayRecoveryVerification.verify_protocol(evidence)
      end
    end
    evidence = fixture
    evidence["timing"]["completedAtEpochMs"] = 1180001
    assert_raises(GatewayRecoveryVerification::InvalidEvidence) do
      GatewayRecoveryVerification.verify_protocol(evidence)
    end
  end

  def business
    JSON.parse(File.read(File.join(__dir__, "fixtures/resting-buy.json")))
  end

  def test_requires_the_same_order_body_and_nonempty_protocol_identity
    {
      "originalExecId" => "", "originalOrderSequence" => 0,
      "testRequestId" => "", "retryOrderBodySha256" => "different-body"
    }.each do |field, value|
      evidence = fixture
      evidence["protocol"][field] = value
      assert_raises(GatewayRecoveryVerification::InvalidEvidence, field) do
        GatewayRecoveryVerification.verify_protocol(evidence)
      end
    end
  end

  def complete_recovery
    fixture.merge("riskAfter" => business.fetch("risk"), "durableAfter" => business.fetch("durable"))
  end

  def test_verifies_the_same_admission_and_business_outcome_after_retry
    Dir.mktmpdir("gateway-recovery-result") do |directory|
      actual = File.join(directory, "result.json")
      File.write(actual, JSON.pretty_generate(GatewayRecoveryVerification.verify(business, complete_recovery)) + "\n")
      assert system("diff", "-u", File.join(__dir__, "baselines/gateway-recovery-result.json"), actual)
    end
    {
      ["riskAfter", "count"] => 2,
      ["riskAfter", "commandId"] => "another-command",
      ["riskAfter", "reservationId"] => "another-reservation",
      ["riskAfter", "outboxCount"] => 2,
      ["durableAfter", "account", "reservationCount"] => 2,
      ["durableAfter", "account", "limitReservedNotional"] => "113800",
      ["protocol", "clOrdId"] => "another-order",
      ["protocol", "accountId"] => "another-account",
      ["final", "gateState"] => "PRE_OPEN"
    }.each do |path, value|
      evidence = complete_recovery
      evidence.dig(*path[0...-1])[path.last] = value
      assert_raises(GatewayRecoveryVerification::InvalidEvidence, path.join(".")) do
        GatewayRecoveryVerification.verify(business, evidence)
      end
    end
  end

  def test_runner_cli_uses_all_observation_files_and_fails_closed_if_one_is_missing
    Dir.mktmpdir("gateway-recovery-cli") do |directory|
      paths = {
        "expected" => "submission/expected.json", "fix" => "fix/submit.json",
        "open" => "baseline/gateway-open.json", "after" => "baseline/gateway-after.json",
        "risk" => "submission/risk-admission.json", "command" => "kafka/matching-command-observation.json",
        "event" => "kafka/matching-event-observation.json", "durable" => "durable-state.json"
      }
      paths.each do |name, path|
        destination = File.join(directory, path)
        FileUtils.mkdir_p(File.dirname(destination))
        File.write(destination, JSON.generate(business.fetch(name)))
      end
      FileUtils.mkdir_p(File.join(directory, "recovery"))
      recovery = complete_recovery
      %w[before after].each do |phase|
        %w[owner session wal journal].each do |name|
          File.write(File.join(directory, "recovery/#{phase}-#{name}.json"), JSON.generate(recovery.fetch(phase).fetch(name)))
        end
      end
      {"protocol" => "protocol", "timing" => "timing", "riskAfter" => "risk-after",
        "durableAfter" => "durable-after", "open" => "gateway-open", "final" => "gateway-final"}.each do |name, path|
        File.write(File.join(directory, "recovery/#{path}.json"), JSON.generate(recovery.fetch(name)))
      end
      File.write(File.join(directory, "recovery/owner-samples.jsonl"), recovery.fetch("samples").map { |sample| JSON.generate(sample) }.join("\n") + "\n")
      command = ["ruby", File.join(__dir__, "../lib/gateway-recovery-verification.rb"), "verify", directory]
      stdout, stderr, status = Open3.capture3(*command)
      assert status.success?, stderr
      assert_equal "gateway-same-owner-recovery", JSON.parse(stdout).fetch("scenario")
      assert system("diff", "-u", File.join(__dir__, "baselines/gateway-recovery-result.json"), File.join(directory, "recovery-result.json"))
      File.delete(File.join(directory, "recovery/before-wal.json"))
      stdout, stderr, status = Open3.capture3(*command)
      refute status.success?
      assert_empty stdout
      assert_includes stderr, "evidence unavailable"
    end
  end
end

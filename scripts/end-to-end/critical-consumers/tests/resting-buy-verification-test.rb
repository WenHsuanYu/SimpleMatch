#!/usr/bin/env ruby
require "json"
require "fileutils"
require "minitest/autorun"
require "tmpdir"
require_relative "../lib/resting-buy-verification"

class RestingBuyVerificationTest < Minitest::Test
  def fixture
    JSON.parse(File.read(File.join(__dir__, "fixtures/resting-buy.json")))
  end

  def test_accepts_the_complete_business_chain_and_matches_reviewable_baseline
    Dir.mktmpdir("resting-buy-result") do |directory|
      actual = File.join(directory, "result.json")
      File.write(actual, JSON.pretty_generate(RestingBuyVerification.verify(fixture)) + "\n")
      baseline = File.join(__dir__, "baselines/resting-buy-result.json")
      assert system("diff", "-u", baseline, actual), "result differs from checked-in baseline"
    end
  end

  def test_checks_identity_and_business_fields_at_each_boundary
    {
      ["fix", "clOrdId"] => "OTHER",
      ["fix", "orderId"] => "O-OTHER",
      ["fix", "accountId"] => "another-account",
      ["fix", "execType"] => "0",
      ["risk", "orderId"] => "0198a000-0000-7000-8000-000000000099",
      ["risk", "count"] => 2,
      ["risk", "outboxCount"] => 0,
      ["risk", "state"] => "REJECTED",
      ["command", "commandId"] => "0198a000-0000-7000-8000-000000000099",
      ["command", "quantityShares"] => 2000,
      ["command", "priceUnits"] => 570000,
      ["command", "partition"] => 5,
      ["command", "artifactContentSha256"] => "wrong-artifact",
      ["event", "context", "sourceInputOffset"] => 13,
      ["event", "restedOrder", "orderId"] => "0198a000-0000-7000-8000-000000000099",
      ["event", "restedOrder", "accountId"] => "another-account",
      ["event", "restedOrder", "leavesQuantityShares"] => 999,
      ["event", "eventType"] => "MATCHING_EVENT_TYPE_TRADE_EXECUTED",
      ["durable", "persistence", "status"] => "PARTIALLY_FILLED",
      ["durable", "persistence", "fillCount"] => 1,
      ["durable", "persistence", "lastEventId"] => "wrong-event",
      ["durable", "account", "reservationCount"] => 2,
      ["durable", "account", "reservationId"] => "wrong-reservation",
      ["durable", "account", "reservedNotional"] => "113800",
      ["durable", "account", "limitReservedNotional"] => "113800",
      ["durable", "account", "remainingQuantity"] => "999",
      ["durable", "account", "limitUtilizedNotional"] => "1",
      ["durable", "account", "limitAvailableNotional"] => "99999999999999943099.00000001",
      ["durable", "events", "accountCount"] => 0,
      ["durable", "events", "persistencePayloadSha256"] => "wrong-payload",
      ["durable", "events", "quickfixPayloadSha256"] => "wrong-payload",
      ["durable", "events", "quarantineCount"] => 1,
      ["open", "accepted"] => false,
      ["after", "gateState"] => "NEW_ORDERS_PAUSED"
    }.each do |path, value|
      evidence = fixture
      evidence.dig(*path[0...-1])[path.last] = value
      assert_raises(RestingBuyVerification::InvalidEvidence, path.join(".")) do
        RestingBuyVerification.verify(evidence)
      end
    end
  end

  def test_correlates_the_fix_pending_identity_without_equating_it_to_the_risk_uuid
    evidence = fixture
    refute_equal evidence.fetch("risk").fetch("orderId"), evidence.fetch("fix").fetch("orderId")
    assert_equal "PASS", RestingBuyVerification.verify(evidence).fetch("status")
  end

  def test_allows_identical_transport_redeliveries_without_double_business_effects
    evidence = fixture
    evidence["command"]["physicalDeliveryCount"] = 2
    assert_equal "PASS", RestingBuyVerification.verify(evidence).fetch("status")
  end

  def test_fails_closed_when_a_required_fact_is_missing
    evidence = fixture
    evidence["durable"]["account"].delete("limitReservedNotional")
    assert_raises(RestingBuyVerification::InvalidEvidence) do
      RestingBuyVerification.verify(evidence)
    end
  end

  def test_publishes_pass_only_after_restoration_and_never_relabels_a_failure
    Dir.mktmpdir("resting-buy-finalize") do |directory|
      File.write(File.join(directory, "source-revision"), "811bd6114d6cfdb59f32cda74cac6632c8ef5c25\n")
      File.write(File.join(directory, "business-result.json"), JSON.generate(RestingBuyVerification.verify(fixture)))
      refute RestingBuyVerification.finalize(directory, ["0", "restore", "true"])
      assert_equal "FAIL", JSON.parse(File.read(File.join(directory, "verdict.json"))).fetch("status")
      refute RestingBuyVerification.finalize(directory, ["1", "observation", "false"])
      assert RestingBuyVerification.finalize(directory, ["0", "completed", "false"])
      verdict = JSON.parse(File.read(File.join(directory, "verdict.json")))
      assert_equal true, verdict.fetch("restorationPassed")
      assert_equal false, verdict.fetch("fullLocalCertification")
    end
  end

  def completed_deployment(directory)
    deployment = JSON.parse(File.read(File.join(__dir__, "fixtures/completed-trading-deployment.json")))
    File.write(File.join(directory, "source-revision"), deployment.fetch("sourceRevision") + "\n")
    File.write(File.join(directory, "report.md"), "- status: #{deployment.fetch('reportStatus')}\n")
    phases = deployment.fetch("phaseIds").map do |phase|
      result = deployment.fetch("result").merge("phaseId" => phase)
      phase_directory = File.join(directory, "phases", phase)
      FileUtils.mkdir_p(phase_directory)
      File.write(File.join(phase_directory, "result.json"), JSON.generate(result))
      result
    end
    File.write(File.join(directory, "evidence-manifest.json"), JSON.generate({"schemaVersion" => 1, "phases" => phases}))
  end

  def test_does_not_publish_baseline_pass_when_requested_recovery_is_incomplete
    Dir.mktmpdir("gateway-recovery-finalize") do |directory|
      File.write(File.join(directory, "source-revision"), "811bd6114d6cfdb59f32cda74cac6632c8ef5c25\n")
      File.write(File.join(directory, "business-result.json"), JSON.generate(RestingBuyVerification.verify(fixture)))
      refute RestingBuyVerification.finalize(directory, ["0", "completed", "false", "true"])
      File.write(File.join(directory, "recovery-result.json"), File.read(File.join(__dir__, "baselines/gateway-recovery-result.json")))
      refute RestingBuyVerification.finalize(directory, ["0", "completed", "false", "true"])
      FileUtils.mkdir_p(File.join(directory, "recovery"))
      File.write(File.join(directory, "recovery/restoration.json"), "")
      refute RestingBuyVerification.finalize(directory, ["0", "completed", "false", "true"])
      assert_equal "FAIL", JSON.parse(File.read(File.join(directory, "verdict.json"))).fetch("status")
      File.write(File.join(directory, "recovery/restoration.json"), JSON.generate({"status" => "PASS", "gatewayReady" => true, "operationsOverridesRemoved" => true, "postRestorationOpenProven" => false}))
      assert RestingBuyVerification.finalize(directory, ["0", "completed", "false", "true"])
      verdict = JSON.parse(File.read(File.join(directory, "verdict.json")))
      assert_equal "gateway-same-owner-recovery", verdict.fetch("scenario")
      assert_includes verdict.fetch("evidence"), "recovery/protocol.json"
      assert_equal true, verdict.fetch("restorationGatewayReady")
      assert_equal false, verdict.fetch("postRestorationOpenProven")
    end
  end

  def test_accepts_completed_kubernetes_deployment_but_not_a_failed_parent
    Dir.mktmpdir("resting-buy-deployment") do |directory|
      completed_deployment(directory)
      actual = File.join(directory, "observed-prerequisites.json")
      File.write(actual, JSON.pretty_generate(RestingBuyVerification.verify_deployment(directory)) + "\n")
      assert system("diff", "-u", File.join(__dir__, "baselines/deployment-prerequisites.json"), actual)
      File.write(File.join(directory, "report.md"), "- status: FAILED\n")
      assert_raises(RestingBuyVerification::InvalidEvidence) do
        RestingBuyVerification.verify_deployment(directory)
      end
    end
  end

  def test_requires_completed_matching_fleet_evidence_from_the_same_source
    %w[missing failed different-source missing-manifest].each do |failure|
      Dir.mktmpdir("resting-buy-deployment") do |directory|
        completed_deployment(directory)
        result_path = File.join(directory, "phases/kubernetes-fleet/result.json")
        result = JSON.parse(File.read(result_path))
        case failure
        when "missing" then File.delete(result_path)
        when "missing-manifest" then File.delete(File.join(directory, "evidence-manifest.json"))
        when "failed"
          result["status"] = "FAIL"
          File.write(result_path, JSON.generate(result))
        when "different-source"
          result["execution"]["sourceRevision"] = "480ded3ead07038a8779b59b365421a141247c43"
          File.write(result_path, JSON.generate(result))
        end
        assert_raises(RestingBuyVerification::InvalidEvidence, failure) do
          RestingBuyVerification.verify_deployment(directory)
        end
      end
    end
  end
end

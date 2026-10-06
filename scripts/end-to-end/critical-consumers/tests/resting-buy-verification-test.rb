#!/usr/bin/env ruby
require "json"
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
      ["risk", "count"] => 2,
      ["risk", "outboxCount"] => 0,
      ["risk", "state"] => "REJECTED",
      ["command", "commandId"] => "0198a000-0000-7000-8000-000000000099",
      ["command", "quantityShares"] => 2000,
      ["command", "priceUnits"] => 570000,
      ["command", "partition"] => 5,
      ["command", "artifactContentSha256"] => "wrong-artifact",
      ["event", "context", "sourceInputOffset"] => 13,
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
end

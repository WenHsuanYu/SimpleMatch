#!/usr/bin/env ruby
require "json"
require "minitest/autorun"
require "tmpdir"
require_relative "../lib/matching-recovery-verification"

class MatchingRecoveryVerificationTest < Minitest::Test
  def fixture
    baseline = JSON.parse(File.read(File.join(__dir__, "fixtures/resting-buy.json")))
    recovery = JSON.parse(File.read(File.join(__dir__, "fixtures/matching-recovery.json")))
    baseline.fetch("durable").fetch("account").merge!(recovery.delete("baselineVersions"))
    baseline.fetch("durable").fetch("persistence")["projectionCount"] = 1
    recovery["baseline"] = baseline
    recovery["durableAfterReplay"] = Marshal.load(Marshal.dump(baseline.fetch("durable")))
    cancelled = Marshal.load(Marshal.dump(baseline.fetch("durable")))
    cancelled.fetch("persistence").merge!("status" => "CANCELLED", "lastEventId" => recovery.dig("cancel", "event", "eventId"))
    cancelled.fetch("account").merge!(recovery.delete("cancelledAccountChanges"))
    %w[persistencePayloadSha256 accountPayloadSha256 quickfixPayloadSha256].each do |field|
      cancelled.fetch("events")[field] = recovery.dig("cancel", "event", "payloadSha256")
    end
    recovery["durableAfterCancel"] = cancelled
    recovery["durableAfterRedelivery"] = Marshal.load(Marshal.dump(cancelled))
    recovery
  end

  def reject_mutations(mutations)
    mutations.each do |path, value|
      evidence = fixture
      evidence.dig(*path[0...-1])[path.last] = value
      assert_raises(RestingBuyVerification::InvalidEvidence, path.join(".")) do
        MatchingRecoveryVerification.verify(evidence)
      end
    end
  end

  def test_complete_contract_matches_reviewable_baseline
    Dir.mktmpdir("matching-recovery-result") do |directory|
      actual = File.join(directory, "result.json")
      File.write(actual, JSON.pretty_generate(MatchingRecoveryVerification.verify(fixture)) + "\n")
      assert system("diff", "-u", File.join(__dir__, "baselines/matching-recovery-result.json"), actual)
    end
  end

  def test_completed_commit_has_no_pending_next_commit_candidate
    evidence = fixture
    evidence.fetch("runtimeAfter")["next_commit_offset"] = nil
    assert_equal "PASS", MatchingRecoveryVerification.verify(evidence).fetch("status")
  end

  # Six risk categories, not six deployments or a resilience matrix.
  def test_rejects_missing_actual_interruption_or_replay
    reject_mutations({
      ["interruption", "oldOwnerInterrupted"] => false,
      ["interruption", "originalPodUid"] => "another-uid",
      ["afterOwner", "podUid"] => "matching-original-uid",
      ["afterOwner", "nodeName"] => "another-node",
      ["afterOwner", "pvcUid"] => "another-claim",
      ["afterOwner", "imageId"] => "another-image",
      ["runtimeAfter", "runtime_state"] => "RUNNING",
      ["runtimeAfter", "highest_contiguous_completed_offset"] => 11,
      ["runtimeAfter", "admission", "recovery_complete"] => false,
      ["runtimeAfter", "pending_publications"] => 1,
      ["timing", "completedAtEpochMs"] => 1787846600000
    })
  end

  def test_rejects_command_order_event_or_content_conflicts
    reject_mutations({
      ["cancel", "risk", "commandId"] => fixture.dig("baseline", "risk", "commandId"),
      ["cancel", "command", "orderId"] => "another-order",
      ["cancel", "command", "partition"] => 5,
      ["cancel", "event", "sourceCommandId"] => "another-command",
      ["cancel", "event", "context", "sourceInputOffset"] => 12,
      ["cancel", "event", "terminalOrder", "accountId"] => "another-account",
      ["cancel", "event", "terminalOrder", "reason"] => "CANCELLATION_REASON_SESSION_EXPIRED",
      ["durableAfterCancel", "events", "accountPayloadSha256"] => "different-bytes"
    })
  end

  def test_rejects_missing_wrong_or_duplicate_persistence_result
    reject_mutations({
      ["durableAfterReplay", "persistence", "status"] => "CANCELLED",
      ["durableAfterCancel", "persistence", "projectionCount"] => 2,
      ["durableAfterCancel", "persistence", "status"] => "RESTING",
      ["durableAfterCancel", "persistence", "lastEventId"] => "wrong-event",
      ["durableAfterCancel", "persistence", "fillCount"] => 1,
      ["durableAfterCancel", "events", "persistenceCount"] => 0,
      ["durableAfterRedelivery", "persistence", "projectionCount"] => 2
    })
  end

  def test_rejects_wrong_or_repeated_account_effects
    reject_mutations({
      ["durableAfterCancel", "account", "reservationCount"] => 2,
      ["durableAfterCancel", "account", "status"] => "RESERVATION_STATUS_ACCEPTED",
      ["durableAfterCancel", "account", "remainingQuantity"] => "1000",
      ["durableAfterCancel", "account", "limitAvailableNotional"] => "99999999999999943099",
      ["durableAfterCancel", "account", "limitUtilizedNotional"] => "1",
      ["durableAfterCancel", "account", "reservationVersion"] => 9,
      ["durableAfterCancel", "account", "limitVersion"] => 13,
      ["durableAfterRedelivery", "account", "reservationVersion"] => 9,
      ["durableAfterRedelivery", "events", "quarantineCount"] => 1
    })
  end

  def test_rejects_ready_without_successful_new_cancel
    reject_mutations({
      ["gatewayOpen", "accepted"] => false,
      ["gatewayFinal", "gateState"] => "NEW_ORDERS_PAUSED",
      ["cancel", "fix", "execType"] => "6",
      ["cancel", "fix", "origClOrdId"] => "another-original",
      ["cancel", "fix", "sentAtEpochMs"] => 1787846407000,
      ["cancel", "risk", "outboxCount"] => 0,
      ["cancel", "event", "eventType"] => "MATCHING_EVENT_TYPE_ORDER_EXPIRED"
    })
  end

  def test_rejects_missing_actual_repeat_delivery
    reject_mutations({
      ["redelivery", "publishedOffset"] => 1,
      ["redelivery", "observedOffset"] => 1,
      ["redelivery", "eventId"] => "another-event",
      ["redelivery", "payloadSha256"] => "different-bytes",
      ["redelivery", "keyBytesEqual"] => false,
      ["redelivery", "valueBytesEqual"] => false,
      ["consumerProgress", "accountLastProcessedOffset"] => 1
    })
    evidence = fixture
    evidence.delete("redelivery")
    assert_raises(RestingBuyVerification::InvalidEvidence) { MatchingRecoveryVerification.verify(evidence) }
  end
end

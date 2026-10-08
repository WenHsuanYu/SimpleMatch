#!/usr/bin/env ruby
require_relative "resting-buy-verification"

# One actual owner replacement followed by cancel and controlled event redelivery.
module MatchingRecoveryVerification
  InvalidEvidence = RestingBuyVerification::InvalidEvidence

  def self.equal(expected, actual, field)
    raise InvalidEvidence, "Matching recovery #{field} does not match the contract" unless expected == actual
  end

  def self.same_fields(expected, actual, fields, boundary)
    fields.each { |field| equal(expected.fetch(field), actual.fetch(field), "#{boundary}.#{field}") }
  end

  def self.verify(evidence)
    baseline = evidence.fetch("baseline")
    RestingBuyVerification.verify(baseline)
    verify_recovery(evidence, baseline)
    equal(baseline.fetch("durable"), evidence.fetch("durableAfterReplay"), "business state after replay")
    verify_cancel(evidence, baseline)
    verify_cancelled_state(evidence, baseline)
    verify_redelivery(evidence)
    {
      "status" => "PASS", "scenario" => "matching-business-recovery",
      "commandId" => baseline.fetch("risk").fetch("commandId"),
      "orderId" => baseline.fetch("risk").fetch("orderId"),
      "partition" => baseline.fetch("risk").fetch("routingPartition"),
      "replacementPodUid" => evidence.fetch("afterOwner").fetch("podUid"),
      "cancelCommandId" => evidence.dig("cancel", "risk", "commandId"),
      "cancelEventId" => evidence.dig("cancel", "event", "eventId"),
      "cancelEventOffset" => evidence.dig("cancel", "event", "offset"),
      "redeliveryOffset" => evidence.fetch("redelivery").fetch("observedOffset"),
      "matchingRecoveryPassed" => true, "postRecoveryCancelPassed" => true,
      "controlledRedeliveryPassed" => true, "restartCausedRedeliveryProven" => false,
      "fullLocalCertification" => false
    }
  rescue KeyError, ArgumentError, TypeError, NoMethodError => failure
    raise InvalidEvidence, "missing or invalid Matching recovery evidence: #{failure.class}"
  end

  def self.verify_recovery(evidence, baseline)
    before = evidence.fetch("beforeOwner")
    after = evidence.fetch("afterOwner")
    same_fields(before, after, %w[podName nodeName pvcName pvcUid pvName pvUid imageId], "same owner")
    %w[podName podUid nodeName pvcName pvcUid pvName pvUid imageId].each do |field|
      [before, after].each { |owner| equal(true, owner.fetch(field).is_a?(String) && !owner.fetch(field).empty?, field) }
    end
    equal("matching-#{baseline.dig('risk', 'routingPartition')}", before.fetch("podName"), "routed owner")
    equal(false, before.fetch("podUid") == after.fetch("podUid"), "replacement UID")
    [before, after].each { |owner| equal(true, owner.fetch("ready"), "owner Ready") }
    interruption = evidence.fetch("interruption")
    equal(before.fetch("podUid"), interruption.fetch("originalPodUid"), "interrupted UID")
    equal(true, interruption.fetch("oldOwnerInterrupted"), "actual interruption")
    timing = evidence.fetch("timing")
    started, recovered, completed, budget = timing.values_at("startedAtEpochMs", "recoveredAtEpochMs", "completedAtEpochMs", "budgetMillis")
    interrupted = interruption.fetch("observedAtEpochMs")
    equal(true, [started, interrupted, recovered, completed, budget].all? { |value| value.is_a?(Integer) && value.positive? }, "clock evidence")
    equal(true, started <= interrupted && interrupted <= recovered && recovered < completed && completed - started <= budget, "bounded recovery")
    runtime = evidence.fetch("runtimeAfter")
    equal(1, runtime.fetch("schema_version"), "runtime schema")
    equal("READY", runtime.fetch("runtime_state"), "runtime readiness")
    equal("OPEN", runtime.fetch("partition_state"), "replayed partition")
    %w[pending_inputs pending_publications].each { |field| equal(0, runtime.fetch(field), field) }
    offset = baseline.fetch("command").fetch("offset")
    equal(true, runtime.fetch("highest_contiguous_completed_offset") >= offset, "replay catch-up")
    updated = runtime.fetch("updated_at_epoch_ms")
    equal(true, updated.is_a?(Integer) && updated <= recovered && recovered - updated <= 5000, "fresh recovered runtime")
    admission = runtime.fetch("admission")
    equal(baseline.dig("risk", "routingPartition"), admission.fetch("partition_id"), "runtime partition")
    equal(after.fetch("podName"), admission.fetch("owner_id"), "runtime owner")
    %w[ownership_permitted recovery_complete].each { |field| equal(true, admission.fetch(field), field) }
    identity = admission.fetch("identity")
    expected = baseline.fetch("expected")
    equal(expected.fetch("tradingSessionId"), identity.fetch("trading_session_id"), "replay session")
    equal(expected.fetch("routingAlgorithmVersion"), identity.fetch("matching_algorithm_version"), "replay algorithm")
    equal("market-reference-#{expected.fetch('tradingDay')}", identity.fetch("artifact").fetch("id"), "replay artifact")
    equal(expected.fetch("artifactContentSha256"), identity.fetch("artifact").fetch("content_sha256"), "replay artifact content")
  end

  def self.verify_cancel(evidence, baseline)
    %w[gatewayOpen gatewayFinal].each do |phase|
      gateway = evidence.fetch(phase)
      equal("OPEN", gateway.fetch("gateState"), phase)
      equal(true, gateway.fetch("tradingSystemStatus").fetch("openEligible"), "#{phase} eligibility")
    end
    equal(true, evidence.fetch("gatewayOpen").fetch("accepted"), "authenticated reopen")
    expected, original = baseline.values_at("expected", "risk")
    fix, risk, command, event = evidence.fetch("cancel").values_at("fix", "risk", "command", "event")
    equal(expected.fetch("clOrdId"), fix.fetch("origClOrdId"), "cancel original ClOrdID")
    equal(expected.fetch("clOrdId"), fix.fetch("reportClOrdId"), "original order terminal FIX report")
    same_fields(baseline.fetch("fix"), fix, %w[accountId orderId], "cancel FIX-facing identity")
    equal("4", fix.fetch("execType"), "cancel FIX ExecType")
    equal("4", fix.fetch("ordStatus"), "cancel FIX OrdStatus")
    equal(true, fix.fetch("sentAtEpochMs") >= evidence.dig("timing", "recoveredAtEpochMs") && fix.fetch("sentAtEpochMs") <= evidence.dig("timing", "completedAtEpochMs"), "new cancel after recovery")
    equal(true, fix.fetch("clOrdId").is_a?(String) && !fix.fetch("clOrdId").empty? && fix.fetch("clOrdId") != expected.fetch("clOrdId"), "new cancel ClOrdID")
    equal(fix.fetch("clOrdId"), risk.fetch("clOrdId"), "cancel journal ClOrdID")
    %w[count outboxCount].each { |field| equal(1, risk.fetch(field), "cancel #{field}") }
    equal("ACCEPTED", risk.fetch("state"), "cancel admission")
    equal(nil, risk.fetch("reservationId"), "cancel does not reserve again")
    same_fields(original, risk, %w[orderId accountId routingPartition venueMic symbol side tradingDay artifactContentSha256 routingAlgorithmVersion], "cancel journal")
    equal(true, risk.fetch("commandId").is_a?(String) && !risk.fetch("commandId").empty? && risk.fetch("commandId") != original.fetch("commandId"), "distinct cancel command")
    equal("matching.commands", command.fetch("topic"), "cancel topic")
    equal("CANCEL_ORDER", command.fetch("commandType"), "new cancel command type")
    same_fields(risk, command, %w[commandId orderId accountId venueMic symbol side tradingDay artifactContentSha256 routingAlgorithmVersion], "cancel command")
    equal(expected.fetch("tradingSessionId"), command.fetch("tradingSessionId"), "cancel command session")
    equal(original.fetch("routingPartition"), command.fetch("partition"), "cancel partition")
    equal(true, command.fetch("physicalDeliveryCount").positive? && command.fetch("offset") > baseline.dig("command", "offset"), "new physical cancel command")
    equal("matching.events", event.fetch("topic"), "cancel event topic")
    equal(command.fetch("partition"), event.fetch("partition"), "cancel event partition")
    equal(risk.fetch("commandId"), event.fetch("sourceCommandId"), "cancel event command")
    equal(original.fetch("orderId"), event.fetch("orderId"), "cancel event order")
    equal("MATCHING_EVENT_TYPE_ORDER_CANCELLED", event.fetch("eventType"), "cancel outcome")
    same_fields(expected, event.fetch("context"), %w[tradingDay tradingSessionId artifactContentSha256 routingAlgorithmVersion], "cancel event context")
    equal(command.fetch("offset"), event.fetch("context").fetch("sourceInputOffset"), "cancel input offset")
    equal(true, event.fetch("offset") >= event.fetch("startOffset") && event.fetch("offset") > baseline.dig("event", "offset"), "new physical cancel event")
    terminal = event.fetch("terminalOrder")
    same_fields(original, terminal, %w[orderId accountId venueMic symbol side], "cancel terminal order")
    equal(expected.fetch("quantityShares"), terminal.fetch("leavesQuantityShares"), "cancelled unfilled quantity")
    equal("CANCELLATION_REASON_USER_REQUEST", terminal.fetch("reason"), "cancel reason")
  end

  def self.verify_cancelled_state(evidence, baseline)
    durable = evidence.fetch("durableAfterCancel")
    before = baseline.fetch("durable")
    event = evidence.dig("cancel", "event")
    projection = durable.fetch("persistence")
    same_fields(before.fetch("persistence"), projection, %w[orderId accountId venueMic symbol side cumulativeQuantityShares leavesQuantityShares fillCount], "cancelled projection")
    equal(1, projection.fetch("projectionCount"), "singular projection")
    equal("CANCELLED", projection.fetch("status"), "durable cancel")
    equal(event.fetch("eventId"), projection.fetch("lastEventId"), "durable cancel event")
    account = durable.fetch("account")
    same_fields(before.fetch("account"), account, %w[reservationCount limitCount reservationId orderId accountId venueMic symbol side tradingDay quantity filledQuantity limitPrice limitTotalNotional limitUtilizedNotional], "released Account")
    equal("RESERVATION_STATUS_RELEASED", account.fetch("status"), "released reservation")
    %w[remainingQuantity reservedNotional limitReservedNotional].each do |field|
      equal(BigDecimal("0"), RestingBuyVerification.decimal(account.fetch(field)), "released #{field}")
    end
    equal(RestingBuyVerification.decimal(account.fetch("limitTotalNotional")), RestingBuyVerification.decimal(account.fetch("limitAvailableNotional")), "all isolated authority returned")
    %w[reservationVersion limitVersion].each do |field|
      version = before.fetch("account").fetch(field)
      equal(true, version.is_a?(Integer) && version >= 0, "baseline #{field}")
      equal(version + 1, account.fetch(field), "one release #{field}")
    end
    RestingBuyVerification.verify_event_consumption(event, durable.fetch("events"))
  end

  def self.verify_redelivery(evidence)
    event = evidence.dig("cancel", "event")
    redelivery = evidence.fetch("redelivery")
    same_fields(event, redelivery, %w[topic partition eventId payloadSha256], "independently observed redelivery")
    equal(event.fetch("offset"), redelivery.fetch("originalOffset"), "original physical event")
    offset = redelivery.fetch("publishedOffset")
    equal(true, offset.is_a?(Integer) && offset > event.fetch("offset"), "distinct physical redelivery")
    equal(offset, redelivery.fetch("observedOffset"), "independent read of new offset")
    %w[keyBytesEqual valueBytesEqual].each { |field| equal(true, redelivery.fetch(field), "byte-identical #{field}") }
    equal(true, /\A[0-9a-f]{64}\z/.match?(redelivery.fetch("keySha256")), "observed Kafka key digest")
    progress = evidence.fetch("consumerProgress")
    equal(event.fetch("partition"), progress.fetch("partition"), "consumer progress partition")
    %w[persistenceLastProcessedOffset accountLastProcessedOffset quickfixLastProcessedOffset].each do |field|
      equal(true, progress.fetch(field).is_a?(Integer) && progress.fetch(field) >= offset, "redelivery #{field}")
    end
    equal(evidence.fetch("durableAfterCancel"), evidence.fetch("durableAfterRedelivery"), "unchanged business state and revisions after redelivery")
  end

  def self.read_evidence(directory)
    recovery = File.join(directory, "matching-recovery")
    evidence = {"baseline" => RestingBuyVerification.read_evidence(directory)}
    {"beforeOwner" => "before-owner", "afterOwner" => "after-owner", "interruption" => "interruption",
      "timing" => "timing", "runtimeAfter" => "runtime-after", "gatewayOpen" => "gateway-open",
      "gatewayFinal" => "gateway-final", "durableAfterReplay" => "durable-after-replay",
      "durableAfterCancel" => "durable-after-cancel", "durableAfterRedelivery" => "durable-after-redelivery",
      "redelivery" => "redelivery", "consumerProgress" => "consumer-progress"}.each do |key, file|
      evidence[key] = JSON.parse(File.read(File.join(recovery, "#{file}.json")))
    end
    evidence["cancel"] = {"fix" => "fix-cancel", "risk" => "risk-cancel", "command" => "matching-command-observation",
      "event" => "matching-event-observation"}.transform_values { |file| JSON.parse(File.read(File.join(recovery, "#{file}.json"))) }
    evidence
  end
end

if $PROGRAM_NAME == __FILE__
  directory = ARGV.fetch(0)
  begin
    result = MatchingRecoveryVerification.verify(MatchingRecoveryVerification.read_evidence(directory))
    File.write(File.join(directory, "matching-recovery-result.json"), JSON.pretty_generate(result) + "\n")
  rescue MatchingRecoveryVerification::InvalidEvidence, Errno::ENOENT, JSON::ParserError => failure
    warn failure.message
    exit 1
  end
end

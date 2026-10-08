#!/usr/bin/env ruby
require "bigdecimal"
require "json"

# Checks a deliberately narrow business scenario, not generic deployment health.
module RestingBuyVerification
  class InvalidEvidence < StandardError; end

  def self.equal(expected, actual, field)
    raise InvalidEvidence, "#{field} does not match the resting-buy contract" unless expected == actual
  end

  def self.decimal(value)
    # SQL numeric values are captured as text; never round money through Float.
    raise InvalidEvidence, "monetary evidence must be decimal text or an integer" unless
      value.is_a?(String) || value.is_a?(Integer)
    BigDecimal(value.to_s)
  end

  def self.same_fields(expected, actual, fields, boundary)
    fields.each { |field| equal(expected.fetch(field), actual.fetch(field), "#{boundary}.#{field}") }
  end

  # A normal order requires completed deployment gates, not a merely live namespace.
  def self.verify_deployment(directory)
    statuses = File.read(File.join(directory, "report.md")).scan(/^- status: (\S+)$/).flatten
    equal(true, statuses.size == 1 && %w[PASS PARTIAL].include?(statuses.first), "deployment completion")
    source = File.read(File.join(directory, "source-revision")).strip
    manifest = JSON.parse(File.read(File.join(directory, "evidence-manifest.json")))
    equal(1, manifest.fetch("schemaVersion"), "deployment manifest schema")
    phases = manifest.fetch("phases")
    equal(Array, phases.class, "deployment phases")
    required = %w[kubernetes-inputs kubernetes-topic-provisioning kubernetes-migrations
      kubernetes-open-barriers kubernetes-risk-outbox-connector kubernetes-account-outbox-connector
      kubernetes-workloads kubernetes-cdc-delivery kubernetes-fleet]
    required.each do |phase|
      entries = phases.select { |entry| entry.fetch("phaseId") == phase }
      equal(1, entries.size, "deployment #{phase} manifest entry")
      result = JSON.parse(File.read(File.join(directory, "phases", phase, "result.json")))
      equal(1, result.fetch("schemaVersion"), "deployment #{phase} result schema")
      same_fields(entries.first, result, %w[phaseId definitionVersion decision status inputFingerprint execution], phase)
      equal("PASS", result.fetch("status"), "deployment #{phase}")
      equal(source, result.fetch("execution").fetch("sourceRevision"), "deployment #{phase} source")
      equal(true, /\Asha256:[0-9a-f]{64}\z/.match?(result.fetch("inputFingerprint")), "deployment #{phase} fingerprint")
    end
    {"status" => "PASS", "deploymentStatus" => statuses.first,
      "sourceRevision" => source, "prerequisitePhases" => required}
  rescue Errno::ENOENT, JSON::ParserError, KeyError, ArgumentError, TypeError => failure
    raise InvalidEvidence, "missing or invalid deployment evidence: #{failure.class}"
  end

  def self.verify(evidence)
    expected = evidence.fetch("expected")
    risk = evidence.fetch("risk")
    command = evidence.fetch("command")
    event = evidence.fetch("event")
    durable = evidence.fetch("durable")
    quantity = expected.fetch("quantityShares")
    price_units = expected.fetch("priceUnits")
    raise InvalidEvidence, "quantity and price must be positive integers" unless
      quantity.is_a?(Integer) && quantity.positive? && price_units.is_a?(Integer) && price_units.positive?

    verify_admission(evidence, expected, risk)
    verify_matching(expected, risk, command, event)
    verify_persistence(expected, risk, event, durable.fetch("persistence"))
    notional = verify_account(expected, risk, durable.fetch("account"))
    verify_event_consumption(event, durable.fetch("events"))
    {
      "status" => "PASS", "scenario" => "resting-buy",
      "commandId" => risk.fetch("commandId"), "orderId" => risk.fetch("orderId"),
      "eventId" => event.fetch("eventId"), "partition" => risk.fetch("routingPartition"),
      "commandOffset" => command.fetch("offset"), "eventOffset" => event.fetch("offset"),
      "quantityShares" => quantity, "priceUnits" => price_units,
      "reservedNotional" => notional.to_s("F")
    }
  rescue KeyError, ArgumentError, TypeError => failure
    raise InvalidEvidence, "missing or invalid business evidence: #{failure.class}"
  end

  def self.verify_admission(evidence, expected, risk)
    %w[open after].each do |phase|
      status = evidence.fetch(phase)
      equal("OPEN", status.fetch("gateState"), "Gateway #{phase}")
      equal(true, status.fetch("tradingSystemStatus").fetch("openEligible"), "Gateway #{phase} readiness")
    end
    equal(true, evidence.fetch("open").fetch("accepted"), "Gateway operator open")
    fix = evidence.fetch("fix")
    same_fields(expected, fix, %w[accountId clOrdId], "FIX")
    # Gateway's Pending New ACK uses its WAL identity, not Risk's derived UUID.
    equal("O-#{expected.fetch('clOrdId')}", fix.fetch("orderId"), "FIX pending orderId")
    equal("A", fix.fetch("execType"), "FIX admission ExecType")
    equal("A", fix.fetch("ordStatus"), "FIX admission OrdStatus")
    equal(1, risk.fetch("count"), "Risk admission count")
    equal(1, risk.fetch("outboxCount"), "Risk new-order outbox count")
    equal("ACCEPTED", risk.fetch("state"), "Risk state")
    same_fields(expected, risk, %w[accountId clOrdId venueMic symbol quantityShares priceUnits tradingDay artifactContentSha256 routingAlgorithmVersion], "Risk")
    equal("SIDE_BUY", risk.fetch("side"), "Risk side")
    partition = risk.fetch("routingPartition")
    raise InvalidEvidence, "Risk route is outside the 15-partition fleet" unless
      partition.is_a?(Integer) && (0..14).cover?(partition)
  end

  def self.verify_matching(expected, risk, command, event)
    equal("matching.commands", command.fetch("topic"), "command topic")
    same_fields(risk, command, %w[commandId orderId], "command")
    same_fields(expected, command, %w[accountId venueMic symbol quantityShares priceUnits tradingDay tradingSessionId artifactContentSha256 routingAlgorithmVersion], "command")
    equal("SIDE_BUY", command.fetch("side"), "command side")
    equal("ORDER_TYPE_LIMIT", command.fetch("orderType"), "command order type")
    equal("TIME_IN_FORCE_ROD", command.fetch("timeInForce"), "command time in force")
    equal(risk.fetch("routingPartition"), command.fetch("partition"), "command partition")
    raise InvalidEvidence, "command observation is empty" unless command.fetch("physicalDeliveryCount").positive?
    equal("matching.events", event.fetch("topic"), "event topic")
    equal(risk.fetch("routingPartition"), event.fetch("partition"), "event partition")
    equal(risk.fetch("commandId"), event.fetch("sourceCommandId"), "event commandId")
    equal(risk.fetch("orderId"), event.fetch("orderId"), "event orderId")
    equal("MATCHING_EVENT_TYPE_ORDER_RESTED", event.fetch("eventType"), "Matching outcome")
    context = event.fetch("context")
    same_fields(expected, context, %w[tradingDay tradingSessionId artifactContentSha256 routingAlgorithmVersion], "event context")
    equal(command.fetch("offset"), context.fetch("sourceInputOffset"), "event source input offset")
    raise InvalidEvidence, "event precedes observation boundary" unless
      event.fetch("offset") >= event.fetch("startOffset")
    rested = event.fetch("restedOrder")
    equal(risk.fetch("orderId"), rested.fetch("orderId"), "decoded rested orderId")
    same_fields(expected, rested, %w[accountId venueMic symbol], "rested order")
    equal("SIDE_BUY", rested.fetch("side"), "rested side")
    equal(expected.fetch("quantityShares"), rested.fetch("leavesQuantityShares"), "rested quantity")
    equal(expected.fetch("priceUnits"), rested.fetch("restingPriceUnits"), "rested price")
  end

  def self.verify_persistence(expected, risk, event, projection)
    equal(risk.fetch("orderId"), projection.fetch("orderId"), "projection orderId")
    same_fields(expected, projection, %w[accountId venueMic symbol], "projection")
    equal(1, projection.fetch("side"), "projection side")
    equal("RESTING", projection.fetch("status"), "projection status")
    equal(0, projection.fetch("cumulativeQuantityShares"), "projection fills")
    equal(expected.fetch("quantityShares"), projection.fetch("leavesQuantityShares"), "projection leaves")
    equal(0, projection.fetch("fillCount"), "durable fill count")
    equal(event.fetch("eventId"), projection.fetch("lastEventId"), "projection eventId")
  end

  def self.verify_account(expected, risk, account)
    equal(1, account.fetch("reservationCount"), "Account reservation count")
    equal(1, account.fetch("limitCount"), "Account limit count")
    same_fields(risk, account, %w[orderId reservationId], "Account")
    same_fields(expected, account, %w[accountId venueMic symbol tradingDay], "Account")
    equal("SIDE_BUY", account.fetch("side"), "Account side")
    equal("RESERVATION_STATUS_ACCEPTED", account.fetch("status"), "Account reservation status")
    quantity = decimal(expected.fetch("quantityShares"))
    price = decimal(expected.fetch("priceUnits")) / 10_000
    notional = quantity * price
    %w[quantity remainingQuantity].each do |field|
      equal(quantity, decimal(account.fetch(field)), "Account #{field}")
    end
    equal(BigDecimal("0"), decimal(account.fetch("filledQuantity")), "Account filled quantity")
    equal(price, decimal(account.fetch("limitPrice")), "Account price")
    %w[reservedNotional limitReservedNotional].each do |field|
      equal(notional, decimal(account.fetch(field)), "Account #{field}")
    end
    equal(BigDecimal("0"), decimal(account.fetch("limitUtilizedNotional")), "Account utilized amount")
    total = decimal(account.fetch("limitTotalNotional"))
    equal(total - notional, decimal(account.fetch("limitAvailableNotional")), "Account available amount")
    notional
  end

  def self.verify_event_consumption(event, consumption)
    %w[persistenceCount accountCount quickfixCount].each do |field|
      equal(1, consumption.fetch(field), "exact event #{field}")
    end
    %w[persistencePayloadSha256 accountPayloadSha256 quickfixPayloadSha256].each do |field|
      equal(event.fetch("payloadSha256"), consumption.fetch(field), "exact event #{field}")
    end
    equal(0, consumption.fetch("quarantineCount"), "critical consumer quarantine count")
  end

  def self.read_evidence(directory)
    {
      "expected" => "submission/expected.json", "fix" => "fix/submit.json",
      "open" => "baseline/gateway-open.json", "after" => "baseline/gateway-after.json",
      "risk" => "submission/risk-admission.json", "command" => "kafka/matching-command-observation.json",
      "event" => "kafka/matching-event-observation.json", "durable" => "durable-state.json"
    }.transform_values { |path| JSON.parse(File.read(File.join(directory, path))) }
  end

  def self.prepare(directory, values)
    fields = %w[accountId clOrdId quantityShares tradingDay tradingSessionId artifactContentSha256 routingAlgorithmVersion]
    raise ArgumentError, "prepare requires #{fields.join(', ')}" unless values.size == fields.size
    expected = fields.zip(values).to_h
    expected["quantityShares"] = Integer(expected.fetch("quantityShares"))
    selection = JSON.parse(File.read(File.join(directory, "submission/selected-instrument.json")))
    expected.merge!(selection.slice("venueMic", "symbol"))
    expected["priceUnits"] = selection.fetch("referencePriceUnits")
    File.write(File.join(directory, "submission/expected.json"), JSON.pretty_generate(expected) + "\n")
  end

  def self.finalize(directory, values)
    exit_status, stage, restoration_failed, recovery_requested, matching_recovery_requested = values
    passed = exit_status == "0" && restoration_failed == "false"
    business_path = File.join(directory, "business-result.json")
    result = read_optional_result(business_path, {"scenario" => "resting-buy"})
    passed &&= result["status"] == "PASS"
    if recovery_requested == "true"
      recovery_path = File.join(directory, "recovery-result.json")
      recovery = read_optional_result(recovery_path, {"scenario" => "gateway-same-owner-recovery"})
      recovery_passed = recovery["status"] == "PASS" && recovery["scenario"] == "gateway-same-owner-recovery" &&
        recovery["protocolRecoveryPassed"] == true && recovery["businessRecoveryPassed"] == true && recovery["infrastructureReady"] == true
      passed &&= recovery_passed
      result.merge!(recovery)
      restoration_path = File.join(directory, "recovery/restoration.json")
      restoration = read_optional_result(restoration_path, {})
      passed &&= restoration["status"] == "PASS" && restoration["gatewayReady"] == true && restoration["operationsOverridesRemoved"] == true
      result.merge!("recoveryGateStateBeforeRestoration" => recovery_passed ? "OPEN" : "NOT_PROVEN",
        "restorationGatewayReady" => restoration["gatewayReady"] == true,
        "postRestorationOpenProven" => false)
    end
    if matching_recovery_requested == "true"
      recovery = read_optional_result(File.join(directory, "matching-recovery-result.json"), {"scenario" => "matching-business-recovery"})
      passed &&= recovery["status"] == "PASS" && recovery["scenario"] == "matching-business-recovery" &&
        %w[matchingRecoveryPassed postRecoveryCancelPassed controlledRedeliveryPassed].all? { |field| recovery[field] == true }
      result.merge!(recovery)
      restoration = read_optional_result(File.join(directory, "recovery/restoration.json"), {})
      passed &&= restoration["status"] == "PASS" && restoration["gatewayReady"] == true && restoration["operationsOverridesRemoved"] == true
      result.merge!("restorationGatewayReady" => restoration["gatewayReady"] == true, "postRestorationOpenProven" => false)
    end
    result.merge!(
      "status" => passed ? "PASS" : "FAIL", "stage" => stage,
      "sourceRevision" => File.read(File.join(directory, "source-revision")).strip,
      "restorationPassed" => restoration_failed == "false",
      "fullLocalCertification" => false,
      "evidence" => %w[baseline/deployment-prerequisites.json baseline/verifier-helper-provenance.json baseline/gateway-open.json fix/submit.json submission/risk-admission.json kafka/matching-command-observation.json kafka/matching-event-observation.json durable-state.json baseline/gateway-after.json]
    )
    if recovery_requested == "true"
      result.fetch("evidence").concat(%w[recovery/before-owner.json recovery/after-owner.json
        recovery/owner-samples.jsonl recovery/before-session.json recovery/after-session.json
        recovery/before-wal.json recovery/after-wal.json recovery/before-journal.json recovery/after-journal.json recovery/protocol.json recovery/timing.json
        recovery/risk-after.json recovery/durable-after.json recovery/gateway-open.json recovery/gateway-final.json recovery/restoration.json])
    end
    if matching_recovery_requested == "true"
      result.fetch("evidence").concat(%w[matching-recovery/before-owner.json matching-recovery/after-owner.json
        matching-recovery/interruption.json matching-recovery/timing.json matching-recovery/runtime-after.json
        matching-recovery/gateway-open.json matching-recovery/gateway-final.json matching-recovery/fix-cancel.json
        matching-recovery/risk-cancel.json matching-recovery/matching-command-observation.json matching-recovery/matching-event-observation.json
        matching-recovery/durable-after-replay.json matching-recovery/durable-after-cancel.json
        matching-recovery/redelivery.json matching-recovery/consumer-progress.json matching-recovery/durable-after-redelivery.json recovery/restoration.json])
    end
    File.write(File.join(directory, "verdict.json"), JSON.pretty_generate(result) + "\n")
    passed
  end

  # A failed producer can leave partial JSON; finalize must still publish FAIL.
  def self.read_optional_result(path, fallback)
    document = JSON.parse(File.read(path))
    document.is_a?(Hash) ? document : fallback
  rescue Errno::ENOENT, JSON::ParserError
    fallback
  end
end

if $PROGRAM_NAME == __FILE__
  operation, directory, *values = ARGV
  abort "usage: resting-buy-verification.rb prepare|deployment|verify|finalize EVIDENCE_DIR [values]" unless directory
  begin
    if operation == "prepare"
      RestingBuyVerification.prepare(directory, values)
    elsif operation == "finalize"
      exit(RestingBuyVerification.finalize(directory, values) ? 0 : 1)
    elsif operation == "deployment"
      puts JSON.pretty_generate(RestingBuyVerification.verify_deployment(directory))
    elsif operation == "verify"
      result = RestingBuyVerification.verify(RestingBuyVerification.read_evidence(directory))
      File.write(File.join(directory, "business-result.json"), JSON.pretty_generate(result) + "\n")
    else
      abort "unknown operation: #{operation}"
    end
  rescue RestingBuyVerification::InvalidEvidence => failure
    warn failure.message
    exit 1
  end
end

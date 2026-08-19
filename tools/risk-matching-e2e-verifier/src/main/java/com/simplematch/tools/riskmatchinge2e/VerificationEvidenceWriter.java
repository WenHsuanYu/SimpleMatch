package com.simplematch.tools.riskmatchinge2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.AdmissionObservation;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.SubmissionObservation;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owns the stable JSON evidence contract emitted by the deployed RM-1 verifier. */
final class VerificationEvidenceWriter {
  private final ObjectMapper json;
  private final Path evidenceDir;

  VerificationEvidenceWriter(ObjectMapper json, Path evidenceDir) {
    this.json = Objects.requireNonNull(json, "JSON mapper is required");
    this.evidenceDir = Objects.requireNonNull(evidenceDir, "evidence directory is required");
  }

  void prepareEmptyDirectory() throws IOException {
    Files.createDirectories(evidenceDir);
    try (var entries = Files.list(evidenceDir)) {
      if (entries.findAny().isPresent()) {
        throw new IllegalStateException(
            "evidence directory must be empty before RM-1 verification: " + evidenceDir);
      }
    }
  }

  void writeSelectedInstrument(RiskMatchingScenario.Scenario scenario) throws IOException {
    final RiskMatchingScenario.RunIdentity run = scenario.run();
    final RiskMatchingScenario.MarketExpectation market = scenario.market();
    final RiskMatchingScenario.CommandIdentity command = scenario.command();
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("runId", run.runId());
    evidence.put("tradingDay", run.tradingDay().toString());
    evidence.put("artifactContentSha256", market.artifactContentSha256());
    evidence.put("routingAlgorithmVersion", market.routingAlgorithmVersion());
    evidence.put("expectedPartition", market.expectedPartition());
    evidence.put("venueMic", market.instrument().instrument().venueMic());
    evidence.put("symbol", market.instrument().instrument().symbol());
    evidence.put("marketRuleId", market.instrument().marketRuleId());
    evidence.put("boardLotShares", market.rule().boardLotShares());
    evidence.put("referencePriceUnits", market.instrument().referencePriceUnits());
    evidence.put("lowerPriceLimitUnits", market.instrument().lowerPriceLimitUnits());
    evidence.put("upperPriceLimitUnits", market.instrument().upperPriceLimitUnits());
    evidence.put("commandId", command.commandId().toString());
    evidence.put("orderId", command.orderId().toString());
    evidence.put("accountId", run.accountId().toString());
    write("selected-instrument.json", evidence);
  }

  void writeRequest(NewOrderCommand request) throws IOException {
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("commandId", request.getCommandId());
    evidence.put("orderId", request.getOrderId());
    evidence.put("accountId", request.getAccountId());
    evidence.put("venueMic", request.getInstrument().getVenueMic());
    evidence.put("symbol", request.getInstrument().getSymbol());
    evidence.put("side", request.getSide().name());
    evidence.put("quantityShares", request.getQuantity().getShares());
    evidence.put("limitPriceUnits", request.getLimitPrice().getUnits());
    evidence.put("orderType", request.getOrderType().name());
    evidence.put("timeInForce", request.getTif().name());
    evidence.put("currency", request.getCurrency().name());
    evidence.put("tradingDay", request.getTradingDay().getIsoDate());
    evidence.put("sessionState", request.getSessionState().name());
    evidence.put("senderCompId", request.getSenderCompId());
    evidence.put("targetCompId", request.getTargetCompId());
    evidence.put("clOrdId", request.getClOrdId());
    write("request.json", evidence);
  }

  void writeSubmission(SubmissionObservation submission) throws IOException {
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("grpcCode", submission.grpcCode().name());
    evidence.put("elapsedMs", submission.elapsedMillis());
    evidence.put("synchronousOutcome", synchronousOutcome(submission));
    submission.response().ifPresent(response -> addSynchronousResponse(evidence, response));
    write("admission-submit.json", evidence);
  }

  void writeAdmissionOutcome(AdmissionObservation outcome) throws IOException {
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("path", outcome.path().name());
    evidence.put("commandId", outcome.commandId());
    evidence.put("orderId", outcome.orderId());
    evidence.put("accountId", outcome.accountId());
    evidence.put("pendingObserved", outcome.pendingObserved());
    evidence.put("reconciliationAttempts", outcome.reconciliationAttempts());
    evidence.put("elapsedMs", outcome.elapsedMillis());
    evidence.put("initialGrpcCode", outcome.initialGrpcCode().name());
    evidence.put("terminalStatus", outcome.terminalStatus());
    write("admission-outcome.json", evidence);
  }

  void writeOffsets(String topic, Map<Integer, Long> offsets) throws IOException {
    write("matching-offsets-before.json", Map.of("topic", topic, "endOffsets", offsets));
  }

  void writeRecord(KafkaMatchingCommandProbe.ProbeResult observed) throws IOException {
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("partition", observed.partition());
    evidence.put("offset", observed.offset());
    evidence.put("timestamp", observed.timestamp());
    evidence.put("physicalDeliveryCount", observed.physicalDeliveryCount());
    evidence.put("key", observed.key());
    evidence.put("payloadSha256", observed.payloadSha256());
    evidence.put("payloadBase64", observed.payloadBase64());
    write("matching-command-record.json", evidence);
  }

  void writeMatchingCommand(MatchingCommand command) throws IOException {
    final var header = command.getHeader();
    final var order = command.getNewOrder();
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("schemaVersion", header.getSchemaVersion());
    evidence.put("commandId", header.getCommandId());
    evidence.put("tradingSessionId", header.getTradingSessionId());
    evidence.put("partitionId", header.getPartitionId());
    evidence.put("artifactTradingDay", header.getArtifactIdentity().getTradingDay());
    evidence.put("artifactContentSha256", header.getArtifactIdentity().getContentSha256());
    evidence.put("routingAlgorithmVersion", header.getRoutingAlgorithmVersion());
    evidence.put("commandType", "NEW_ORDER");
    evidence.put("orderId", order.getOrderId());
    evidence.put("accountId", order.getAccountId());
    evidence.put("venueMic", order.getInstrument().getVenueMic());
    evidence.put("symbol", order.getInstrument().getSymbol());
    evidence.put("side", order.getSide().name());
    evidence.put("quantityShares", order.getQuantityShares());
    evidence.put("limitPriceUnits", order.getLimitPriceUnits());
    evidence.put("orderType", order.getOrderType().name());
    evidence.put("timeInForce", order.getTimeInForce().name());
    write("matching-command-decoded.json", evidence);
  }

  void writePass(
      AdmissionObservation admission,
      KafkaMatchingCommandProbe.ProbeResult observed) throws IOException {
    final Map<String, Object> verdict = new LinkedHashMap<>();
    verdict.put("status", "PASS");
    verdict.put("stage", "COMPLETE");
    verdict.put("commandId", admission.commandId());
    verdict.put("admissionPath", admission.path().name());
    verdict.put("partition", observed.partition());
    verdict.put("physicalDeliveryCount", observed.physicalDeliveryCount());
    verdict.put("payloadSha256", observed.payloadSha256());
    write("verifier-verdict.json", verdict);
  }

  void writeFailure(Throwable failure, String commandId) throws IOException {
    final Map<String, Object> verdict = new LinkedHashMap<>();
    verdict.put("status", "FAIL");
    if (failure instanceof VerificationFailure verification) {
      verdict.put("stage", verification.stage().name());
      verdict.put("failureCode", verification.code().name());
    } else {
      verdict.put("stage", "UNEXPECTED");
      verdict.put("failureCode", "UNEXPECTED_FAILURE");
    }
    verdict.put("reason", safeMessage(failure));
    if (commandId != null && !commandId.isBlank()) {
      verdict.put("commandId", commandId);
    }
    write("verifier-verdict.json", verdict);
  }

  private String synchronousOutcome(SubmissionObservation submission) {
    if (submission.grpcCode() != Status.Code.OK) {
      return "UNCERTAIN";
    }
    final OrderAdmissionResponse response = submission.response().orElse(null);
    if (response == null) {
      return "MISSING";
    }
    if (response.hasAccepted()) {
      return "ACCEPTED";
    }
    if (response.hasRejected()) {
      return "REJECTED";
    }
    return "MISSING";
  }

  private void addSynchronousResponse(
      Map<String, Object> evidence, OrderAdmissionResponse response) {
    if (response.hasAccepted()) {
      final var accepted = response.getAccepted();
      evidence.put("commandId", accepted.getCommandId());
      evidence.put("orderId", accepted.getOrderId());
      evidence.put("accountId", accepted.getAccountId());
      evidence.put("routingPartition", accepted.getRoutingPartition());
      return;
    }
    if (response.hasRejected()) {
      final var rejected = response.getRejected();
      evidence.put("commandId", rejected.getCommandId());
      evidence.put("orderId", rejected.getOrderId());
      evidence.put("accountId", rejected.getAccountId());
      evidence.put("reasonCode", rejected.getReason().name());
      evidence.put("reasonDetail", rejected.getReasonDetail());
    }
  }

  private void write(String fileName, Object value) throws IOException {
    json.writerWithDefaultPrettyPrinter().writeValue(evidenceDir.resolve(fileName).toFile(), value);
  }

  private static String safeMessage(Throwable failure) {
    final String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getName() : message;
  }
}

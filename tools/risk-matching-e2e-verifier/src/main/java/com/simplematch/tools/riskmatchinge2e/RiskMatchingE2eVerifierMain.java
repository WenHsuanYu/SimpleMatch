package com.simplematch.tools.riskmatchinge2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.contracts.risk.v2.OrderAdmissionServiceGrpc;
import com.simplematch.marketreference.MarketReferenceArtifactStartupValidator;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Deployed RM-1 verifier entry point.
 *
 * <p>The process is designed to run inside the same Kubernetes namespace as Risk and Kafka. It
 * validates the exact mounted Market Reference bytes, snapshots all 15 Kafka partition end offsets,
 * submits one real v2 Risk gRPC order, then consumes and decodes the resulting MatchingCommand from
 * the production {@code matching.commands} topic.
 *
 * <p>PostgreSQL/outbox inspection intentionally remains in the outer shell harness because the
 * repository already has a PostgreSQL administrative container there. This keeps database
 * credentials out of this process and makes the Java helper responsible only for typed contracts
 * that shell must not reimplement.
 */
public final class RiskMatchingE2eVerifierMain {
  private RiskMatchingE2eVerifierMain() {}

  /** Runs the verifier and writes bounded JSON evidence before returning. */
  public static void main(String[] args) throws Exception {
    final Arguments arguments = Arguments.parse(args);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    Files.createDirectories(arguments.evidenceDir());

    try {
      run(arguments, json);
    } catch (Exception failure) {
      writeJson(
          json,
          arguments.evidenceDir().resolve("verifier-verdict.json"),
          Map.of("status", "FAIL", "reason", safeMessage(failure)));
      throw failure;
    }
  }

  private static void run(Arguments arguments, ObjectMapper json) throws Exception {
    final byte[] artifactBytes = Files.readAllBytes(arguments.artifactPath());
    final String externalChecksum = Files.readString(arguments.checksumPath()).trim();
    final VerifiedMarketReferenceArtifact verified =
        new MarketReferenceArtifactStartupValidator(json)
            .validate(artifactBytes, externalChecksum, arguments.tradingDay());

    final RiskMatchingScenario.Scenario scenario =
        RiskMatchingScenario.create(
            verified,
            arguments.tradingDay(),
            arguments.accountId(),
            arguments.runId(),
            Instant.now());

    writeJson(json, arguments.evidenceDir().resolve("selected-instrument.json"), selectedEvidence(scenario));
    writeJson(json, arguments.evidenceDir().resolve("request.json"), requestEvidence(scenario.request()));

    final ManagedChannel channel =
        ManagedChannelBuilder.forTarget(arguments.riskTarget()).usePlaintext().build();
    try (KafkaMatchingCommandProbe kafka =
        new KafkaMatchingCommandProbe(
            arguments.kafkaBootstrap(), arguments.topic(), arguments.runId())) {
      final Map<Integer, Long> offsetsBefore = kafka.snapshotEndOffsets();
      writeJson(
          json,
          arguments.evidenceDir().resolve("matching-offsets-before.json"),
          Map.of("topic", arguments.topic(), "endOffsets", offsetsBefore));

      final OrderAdmissionResponse response =
          OrderAdmissionServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(arguments.timeout().toSeconds(), TimeUnit.SECONDS)
              .submitNewOrder(scenario.request());
      RiskMatchingScenario.validateAcceptedResponse(scenario, response);
      writeJson(json, arguments.evidenceDir().resolve("response.json"), responseEvidence(response));

      final KafkaMatchingCommandProbe.ProbeResult observed =
          kafka.awaitCommand(
              scenario.commandId().toString(),
              scenario.expectedPartition(),
              offsetsBefore,
              arguments.timeout());
      RiskMatchingScenario.validateMatchingCommand(scenario, observed.command());

      writeJson(
          json,
          arguments.evidenceDir().resolve("matching-command-record.json"),
          recordEvidence(observed));
      writeJson(
          json,
          arguments.evidenceDir().resolve("matching-command-decoded.json"),
          matchingEvidence(observed.command()));

      writeJson(
          json,
          arguments.evidenceDir().resolve("verifier-verdict.json"),
          Map.of(
              "status",
              "PASS",
              "commandId",
              scenario.commandId().toString(),
              "partition",
              observed.partition(),
              "physicalDeliveryCount",
              observed.physicalDeliveryCount(),
              "payloadSha256",
              observed.payloadSha256()));
    } finally {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static Map<String, Object> selectedEvidence(RiskMatchingScenario.Scenario scenario) {
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("runId", scenario.runId());
    evidence.put("tradingDay", scenario.tradingDay().toString());
    evidence.put("artifactContentSha256", scenario.artifactContentSha256());
    evidence.put("routingAlgorithmVersion", scenario.routingAlgorithmVersion());
    evidence.put("expectedPartition", scenario.expectedPartition());
    evidence.put("venueMic", scenario.instrument().instrument().venueMic());
    evidence.put("symbol", scenario.instrument().instrument().symbol());
    evidence.put("marketRuleId", scenario.instrument().marketRuleId());
    evidence.put("boardLotShares", scenario.rule().boardLotShares());
    evidence.put("referencePriceUnits", scenario.instrument().referencePriceUnits());
    evidence.put("lowerPriceLimitUnits", scenario.instrument().lowerPriceLimitUnits());
    evidence.put("upperPriceLimitUnits", scenario.instrument().upperPriceLimitUnits());
    evidence.put("commandId", scenario.commandId().toString());
    evidence.put("orderId", scenario.orderId().toString());
    evidence.put("accountId", scenario.accountId().toString());
    return evidence;
  }

  private static Map<String, Object> requestEvidence(NewOrderCommand request) {
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
    return evidence;
  }

  private static Map<String, Object> responseEvidence(OrderAdmissionResponse response) {
    final var accepted = response.getAccepted();
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("accepted", true);
    evidence.put("commandId", accepted.getCommandId());
    evidence.put("orderId", accepted.getOrderId());
    evidence.put("accountId", accepted.getAccountId());
    evidence.put("routingSnapshotId", accepted.getRoutingSnapshotId());
    evidence.put("routingPolicyId", accepted.getRoutingPolicyId());
    evidence.put("routingPartition", accepted.getRoutingPartition());
    return evidence;
  }

  private static Map<String, Object> recordEvidence(
      KafkaMatchingCommandProbe.ProbeResult observed) {
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("partition", observed.partition());
    evidence.put("offset", observed.offset());
    evidence.put("timestamp", observed.timestamp());
    evidence.put("physicalDeliveryCount", observed.physicalDeliveryCount());
    evidence.put("key", observed.key());
    evidence.put("payloadSha256", observed.payloadSha256());
    // Base64 is retained specifically so the shell harness can compare the exact Risk outbox BYTEA
    // with the exact Kafka value without teaching shell how to parse protobuf.
    evidence.put("payloadBase64", observed.payloadBase64());
    return evidence;
  }

  private static Map<String, Object> matchingEvidence(MatchingCommand command) {
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
    return evidence;
  }

  private static void writeJson(ObjectMapper json, Path path, Object value) throws IOException {
    json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
  }

  private static String safeMessage(Throwable failure) {
    final String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getName() : message;
  }

  /** Parsed CLI options kept explicit so the Kubernetes harness has a stable, reviewable contract. */
  record Arguments(
      Path artifactPath,
      Path checksumPath,
      LocalDate tradingDay,
      UUID accountId,
      String runId,
      String riskTarget,
      String kafkaBootstrap,
      String topic,
      Duration timeout,
      Path evidenceDir) {
    private static final String DEFAULT_RISK_TARGET = "risk-service:50052";
    private static final String DEFAULT_KAFKA_BOOTSTRAP = "kafka:9092";
    private static final String DEFAULT_TOPIC = "matching.commands";

    Arguments {
      Objects.requireNonNull(artifactPath, "artifact path is required");
      Objects.requireNonNull(checksumPath, "checksum path is required");
      Objects.requireNonNull(tradingDay, "trading day is required");
      Objects.requireNonNull(accountId, "account id is required");
      requireText(runId, "run id");
      requireText(riskTarget, "Risk target");
      requireText(kafkaBootstrap, "Kafka bootstrap");
      requireText(topic, "topic");
      Objects.requireNonNull(timeout, "timeout is required");
      Objects.requireNonNull(evidenceDir, "evidence directory is required");
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("timeout must be positive");
      }
    }

    static Arguments parse(String[] args) {
      final Map<String, String> values = new LinkedHashMap<>();
      for (int index = 0; index < args.length; index++) {
        final String key = args[index];
        if (!key.startsWith("--") || index + 1 >= args.length) {
          throw usage("expected --option value pairs");
        }
        if (values.put(key, args[++index]) != null) {
          throw usage("duplicate option: " + key);
        }
      }

      return new Arguments(
          Path.of(required(values, "--artifact-path")),
          Path.of(required(values, "--checksum-path")),
          LocalDate.parse(required(values, "--trading-day")),
          UUID.fromString(required(values, "--account-id")),
          required(values, "--run-id"),
          values.getOrDefault("--risk-target", DEFAULT_RISK_TARGET),
          values.getOrDefault("--kafka-bootstrap", DEFAULT_KAFKA_BOOTSTRAP),
          values.getOrDefault("--topic", DEFAULT_TOPIC),
          Duration.ofSeconds(
              Long.parseLong(values.getOrDefault("--timeout-seconds", "60"))),
          Path.of(required(values, "--evidence-dir")));
    }

    private static String required(Map<String, String> values, String key) {
      final String value = values.get(key);
      if (value == null || value.isBlank()) {
        throw usage("missing required option " + key);
      }
      return value;
    }

    private static IllegalArgumentException usage(String reason) {
      return new IllegalArgumentException(
          reason
              + System.lineSeparator()
              + "Usage: --artifact-path PATH --checksum-path PATH --trading-day YYYY-MM-DD "
              + "--account-id UUID --run-id ID --evidence-dir PATH "
              + "[--risk-target HOST:PORT] [--kafka-bootstrap HOST:PORT] "
              + "[--topic matching.commands] [--timeout-seconds N]");
    }

    private static String requireText(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " is required");
      }
      return value;
    }
  }
}

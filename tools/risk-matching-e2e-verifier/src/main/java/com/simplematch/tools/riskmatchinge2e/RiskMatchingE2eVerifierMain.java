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
    final ObjectMapper json =
        new ObjectMapper().findAndRegisterModules();
    final Path evidenceDir = arguments.execution().evidenceDir();

    Files.createDirectories(evidenceDir);

    try {
      run(arguments, json);
    } catch (Exception failure) {
      writeJson(
          json,
          evidenceDir.resolve("verifier-verdict.json"),
          Map.of(
              "status",
              "FAIL",
              "reason",
              safeMessage(failure)));
      throw failure;
    }
  }

  /** Executes one complete deployed RM-1 verification run. */
  private static void run(
      Arguments arguments,
      ObjectMapper json) throws Exception {
    final ArtifactFiles artifact = arguments.artifact();
    final RiskMatchingScenario.RunIdentity runIdentity = arguments.run();
    final ServiceEndpoints services = arguments.services();
    final ExecutionOptions execution = arguments.execution();
    final Path evidenceDir = execution.evidenceDir();

    final byte[] artifactBytes =
        Files.readAllBytes(artifact.artifactPath());
    final String externalChecksum =
        Files.readString(artifact.checksumPath()).trim();

    final VerifiedMarketReferenceArtifact verified =
        new MarketReferenceArtifactStartupValidator(json)
            .validate(
                artifactBytes,
                externalChecksum,
                runIdentity.tradingDay());

    final RiskMatchingScenario.Scenario scenario =
        RiskMatchingScenario.create(
            verified,
            runIdentity,
            Instant.now());

    writeJson(
        json,
        evidenceDir.resolve("selected-instrument.json"),
        selectedEvidence(scenario));
    writeJson(
        json,
        evidenceDir.resolve("request.json"),
        requestEvidence(scenario.request()));

    final ManagedChannel channel =
        ManagedChannelBuilder.forTarget(services.riskTarget())
            .usePlaintext()
            .build();

    try (KafkaMatchingCommandProbe kafka =
        new KafkaMatchingCommandProbe(
            services.kafkaBootstrap(),
            services.topic(),
            runIdentity.runId())) {
      final Map<Integer, Long> offsetsBefore =
          kafka.snapshotEndOffsets();

      writeJson(
          json,
          evidenceDir.resolve("matching-offsets-before.json"),
          Map.of(
              "topic",
              services.topic(),
              "endOffsets",
              offsetsBefore));

      final OrderAdmissionResponse response =
          OrderAdmissionServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(
                  execution.timeout().toSeconds(),
                  TimeUnit.SECONDS)
              .submitNewOrder(scenario.request());

      RiskMatchingScenario.validateAcceptedResponse(
          scenario,
          response);

      writeJson(
          json,
          evidenceDir.resolve("response.json"),
          responseEvidence(response));

      final KafkaMatchingCommandProbe.ProbeResult observed =
          kafka.awaitCommand(
              scenario.command().commandId().toString(),
              scenario.market().expectedPartition(),
              offsetsBefore,
              execution.timeout());

      RiskMatchingScenario.validateMatchingCommand(
          scenario,
          observed.command());

      writeJson(
          json,
          evidenceDir.resolve("matching-command-record.json"),
          recordEvidence(observed));
      writeJson(
          json,
          evidenceDir.resolve("matching-command-decoded.json"),
          matchingEvidence(observed.command()));

      writeJson(
          json,
          evidenceDir.resolve("verifier-verdict.json"),
          Map.of(
              "status",
              "PASS",
              "commandId",
              scenario.command().commandId().toString(),
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

  /** Builds evidence for the artifact-derived scenario selection. */
  private static Map<String, Object> selectedEvidence(
      RiskMatchingScenario.Scenario scenario) {
    final RiskMatchingScenario.RunIdentity runIdentity = scenario.run();
    final RiskMatchingScenario.MarketExpectation market =
        scenario.market();
    final RiskMatchingScenario.CommandIdentity command =
        scenario.command();

    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("runId", runIdentity.runId());
    evidence.put("tradingDay", runIdentity.tradingDay().toString());
    evidence.put(
        "artifactContentSha256",
        market.artifactContentSha256());
    evidence.put(
        "routingAlgorithmVersion",
        market.routingAlgorithmVersion());
    evidence.put(
        "expectedPartition",
        market.expectedPartition());
    evidence.put(
        "venueMic",
        market.instrument().instrument().venueMic());
    evidence.put(
        "symbol",
        market.instrument().instrument().symbol());
    evidence.put(
        "marketRuleId",
        market.instrument().marketRuleId());
    evidence.put(
        "boardLotShares",
        market.rule().boardLotShares());
    evidence.put(
        "referencePriceUnits",
        market.instrument().referencePriceUnits());
    evidence.put(
        "lowerPriceLimitUnits",
        market.instrument().lowerPriceLimitUnits());
    evidence.put(
        "upperPriceLimitUnits",
        market.instrument().upperPriceLimitUnits());
    evidence.put(
        "commandId",
        command.commandId().toString());
    evidence.put(
        "orderId",
        command.orderId().toString());
    evidence.put(
        "accountId",
        runIdentity.accountId().toString());

    return evidence;
  }

  /** Builds evidence for the exact NewOrder request submitted to Risk. */
  private static Map<String, Object> requestEvidence(
      NewOrderCommand request) {
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("commandId", request.getCommandId());
    evidence.put("orderId", request.getOrderId());
    evidence.put("accountId", request.getAccountId());
    evidence.put(
        "venueMic",
        request.getInstrument().getVenueMic());
    evidence.put(
        "symbol",
        request.getInstrument().getSymbol());
    evidence.put("side", request.getSide().name());
    evidence.put(
        "quantityShares",
        request.getQuantity().getShares());
    evidence.put(
        "limitPriceUnits",
        request.getLimitPrice().getUnits());
    evidence.put(
        "orderType",
        request.getOrderType().name());
    evidence.put(
        "timeInForce",
        request.getTif().name());
    evidence.put("currency", request.getCurrency().name());
    evidence.put(
        "tradingDay",
        request.getTradingDay().getIsoDate());
    evidence.put(
        "sessionState",
        request.getSessionState().name());
    evidence.put(
        "senderCompId",
        request.getSenderCompId());
    evidence.put(
        "targetCompId",
        request.getTargetCompId());
    evidence.put("clOrdId", request.getClOrdId());

    return evidence;
  }

  /** Builds evidence for the accepted synchronous Risk response. */
  private static Map<String, Object> responseEvidence(
      OrderAdmissionResponse response) {
    final var accepted = response.getAccepted();
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("accepted", true);
    evidence.put("commandId", accepted.getCommandId());
    evidence.put("orderId", accepted.getOrderId());
    evidence.put("accountId", accepted.getAccountId());
    evidence.put(
        "routingSnapshotId",
        accepted.getRoutingSnapshotId());
    evidence.put(
        "routingPolicyId",
        accepted.getRoutingPolicyId());
    evidence.put(
        "routingPartition",
        accepted.getRoutingPartition());

    return evidence;
  }

  /** Builds evidence for the observed physical Kafka record. */
  private static Map<String, Object> recordEvidence(
      KafkaMatchingCommandProbe.ProbeResult observed) {
    final Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("partition", observed.partition());
    evidence.put("offset", observed.offset());
    evidence.put("timestamp", observed.timestamp());
    evidence.put(
        "physicalDeliveryCount",
        observed.physicalDeliveryCount());
    evidence.put("key", observed.key());
    evidence.put(
        "payloadSha256",
        observed.payloadSha256());
    // Base64 lets shell compare the exact outbox BYTEA with the exact Kafka value.
    evidence.put(
        "payloadBase64",
        observed.payloadBase64());

    return evidence;
  }

  /** Builds evidence for the decoded matching command. */
  private static Map<String, Object> matchingEvidence(
      MatchingCommand command) {
    final var header = command.getHeader();
    final var order = command.getNewOrder();
    final Map<String, Object> evidence = new LinkedHashMap<>();

    evidence.put(
        "schemaVersion",
        header.getSchemaVersion());
    evidence.put(
        "commandId",
        header.getCommandId());
    evidence.put(
        "tradingSessionId",
        header.getTradingSessionId());
    evidence.put(
        "partitionId",
        header.getPartitionId());
    evidence.put(
        "artifactTradingDay",
        header.getArtifactIdentity().getTradingDay());
    evidence.put(
        "artifactContentSha256",
        header.getArtifactIdentity().getContentSha256());
    evidence.put(
        "routingAlgorithmVersion",
        header.getRoutingAlgorithmVersion());
    evidence.put("commandType", "NEW_ORDER");
    evidence.put("orderId", order.getOrderId());
    evidence.put("accountId", order.getAccountId());
    evidence.put(
        "venueMic",
        order.getInstrument().getVenueMic());
    evidence.put(
        "symbol",
        order.getInstrument().getSymbol());
    evidence.put("side", order.getSide().name());
    evidence.put(
        "quantityShares",
        order.getQuantityShares());
    evidence.put(
        "limitPriceUnits",
        order.getLimitPriceUnits());
    evidence.put(
        "orderType",
        order.getOrderType().name());
    evidence.put(
        "timeInForce",
        order.getTimeInForce().name());

    return evidence;
  }

  /** Writes one JSON evidence document with stable pretty formatting. */
  private static void writeJson(
      ObjectMapper json,
      Path path,
      Object value) throws IOException {
    json.writerWithDefaultPrettyPrinter()
        .writeValue(path.toFile(), value);
  }

  /** Returns a safe failure message for the verdict evidence. */
  private static String safeMessage(Throwable failure) {
    final String message = failure.getMessage();

    return message == null || message.isBlank()
        ? failure.getClass().getName()
        : message;
  }

  /**
   * Paths identifying the mounted Market Reference artifact and its checksum.
   *
   * @param artifactPath the mounted final artifact path
   * @param checksumPath the mounted external checksum path
   */
  record ArtifactFiles(
      Path artifactPath,
      Path checksumPath) {

    /** Requires both mounted artifact paths. */
    ArtifactFiles {
      Objects.requireNonNull(
          artifactPath,
          "artifact path is required");
      Objects.requireNonNull(
          checksumPath,
          "checksum path is required");
    }
  }

  /**
   * Runtime endpoints used by the verifier to reach Risk and Kafka.
   *
   * @param riskTarget the Risk gRPC target
   * @param kafkaBootstrap the Kafka bootstrap address
   * @param topic the Matching command topic
   */
  record ServiceEndpoints(
      String riskTarget,
      String kafkaBootstrap,
      String topic) {
    private static final String DEFAULT_RISK_TARGET =
        "risk-service:50052";
    private static final String DEFAULT_KAFKA_BOOTSTRAP =
        "kafka:9092";
    private static final String DEFAULT_TOPIC =
        "matching.commands";

    /** Requires non-blank service endpoint configuration. */
    ServiceEndpoints {
      requireText(riskTarget, "Risk target");
      requireText(kafkaBootstrap, "Kafka bootstrap");
      requireText(topic, "topic");
    }
  }

  /**
   * Execution controls that bound the verifier and locate generated evidence.
   *
   * @param timeout the maximum Risk and Kafka observation duration
   * @param evidenceDir the directory receiving JSON evidence
   */
  record ExecutionOptions(
      Duration timeout,
      Path evidenceDir) {

    /** Requires a positive timeout and an evidence directory. */
    ExecutionOptions {
      Objects.requireNonNull(timeout, "timeout is required");
      Objects.requireNonNull(
          evidenceDir,
          "evidence directory is required");

      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException(
            "timeout must be positive");
      }
    }
  }

  /**
   * Parsed CLI options grouped by artifact, run, service, and execution responsibility.
   *
   * @param artifact the mounted Market Reference files
   * @param run the verifier run identity
   * @param services the Risk and Kafka endpoints
   * @param execution the verifier execution controls
   */
  record Arguments(
      ArtifactFiles artifact,
      RiskMatchingScenario.RunIdentity run,
      ServiceEndpoints services,
      ExecutionOptions execution) {

    /** Requires all parsed CLI option groups. */
    Arguments {
      Objects.requireNonNull(
          artifact,
          "artifact files are required");
      Objects.requireNonNull(
          run,
          "run identity is required");
      Objects.requireNonNull(
          services,
          "service endpoints are required");
      Objects.requireNonNull(
          execution,
          "execution options are required");
    }

    /** Parses stable {@code --option value} CLI pairs into grouped arguments. */
    static Arguments parse(String[] args) {
      final Map<String, String> values =
          parsePairs(args);

      final ArtifactFiles artifact =
          new ArtifactFiles(
              Path.of(required(values, "--artifact-path")),
              Path.of(required(values, "--checksum-path")));

      final RiskMatchingScenario.RunIdentity runIdentity =
          new RiskMatchingScenario.RunIdentity(
              required(values, "--run-id"),
              LocalDate.parse(
                  required(values, "--trading-day")),
              UUID.fromString(
                  required(values, "--account-id")));

      final ServiceEndpoints services =
          new ServiceEndpoints(
              values.getOrDefault(
                  "--risk-target",
                  ServiceEndpoints.DEFAULT_RISK_TARGET),
              values.getOrDefault(
                  "--kafka-bootstrap",
                  ServiceEndpoints.DEFAULT_KAFKA_BOOTSTRAP),
              values.getOrDefault(
                  "--topic",
                  ServiceEndpoints.DEFAULT_TOPIC));

      final ExecutionOptions execution =
          new ExecutionOptions(
              Duration.ofSeconds(
                  Long.parseLong(
                      values.getOrDefault(
                          "--timeout-seconds",
                          "60"))),
              Path.of(
                  required(values, "--evidence-dir")));

      return new Arguments(
          artifact,
          runIdentity,
          services,
          execution);
    }

    /** Parses CLI arguments as unique {@code --option value} pairs. */
    private static Map<String, String> parsePairs(
        String[] args) {
      final Map<String, String> values =
          new LinkedHashMap<>();

      for (int index = 0; index < args.length; index++) {
        final String key = args[index];

        if (!key.startsWith("--") || index + 1 >= args.length) {
          throw usage("expected --option value pairs");
        }

        if (values.put(key, args[++index]) != null) {
          throw usage("duplicate option: " + key);
        }
      }

      return values;
    }

    /** Returns one required non-blank CLI option. */
    private static String required(
        Map<String, String> values,
        String key) {
      final String value = values.get(key);

      if (value == null || value.isBlank()) {
        throw usage("missing required option " + key);
      }

      return value;
    }

    /** Builds a CLI usage error with the stable verifier contract. */
    private static IllegalArgumentException usage(
        String reason) {
      return new IllegalArgumentException(
          reason
              + System.lineSeparator()
              + "Usage: --artifact-path PATH --checksum-path PATH "
              + "--trading-day YYYY-MM-DD --account-id UUID "
              + "--run-id ID --evidence-dir PATH "
              + "[--risk-target HOST:PORT] "
              + "[--kafka-bootstrap HOST:PORT] "
              + "[--topic matching.commands] "
              + "[--timeout-seconds N]");
    }
  }

  /** Requires a non-blank text value. */
  private static String requireText(
      String value,
      String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          field + " is required");
    }

    return value;
  }
}
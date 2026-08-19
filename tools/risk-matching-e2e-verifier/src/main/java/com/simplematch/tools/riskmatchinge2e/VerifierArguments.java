package com.simplematch.tools.riskmatchinge2e;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Parses and groups the stable command-line contract for the deployed RM-1 verifier. */
record VerifierArguments(
    ArtifactFiles artifact,
    RiskMatchingScenario.RunIdentity run,
    ServiceEndpoints services,
    ExecutionOptions execution) {
  private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

  VerifierArguments {
    Objects.requireNonNull(artifact, "artifact files are required");
    Objects.requireNonNull(run, "run identity is required");
    Objects.requireNonNull(services, "service endpoints are required");
    Objects.requireNonNull(execution, "execution options are required");
  }

  static VerifierArguments parse(String[] args) {
    final Map<String, String> values = parsePairs(args);
    final ArtifactFiles artifact =
        new ArtifactFiles(
            Path.of(required(values, "--artifact-path")),
            Path.of(required(values, "--checksum-path")));
    final RiskMatchingScenario.RunIdentity run =
        new RiskMatchingScenario.RunIdentity(
            required(values, "--run-id"),
            LocalDate.parse(required(values, "--trading-day")),
            UUID.fromString(required(values, "--account-id")));
    final ServiceEndpoints services =
        new ServiceEndpoints(
            values.getOrDefault("--risk-target", ServiceEndpoints.DEFAULT_RISK_TARGET),
            values.getOrDefault("--kafka-bootstrap", ServiceEndpoints.DEFAULT_KAFKA_BOOTSTRAP),
            values.getOrDefault("--topic", ServiceEndpoints.DEFAULT_TOPIC));
    final Duration timeout =
        Duration.ofSeconds(Long.parseLong(values.getOrDefault("--timeout-seconds", "90")));
    if (timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw usage("--timeout-seconds must not exceed 300");
    }
    final ExecutionOptions execution =
        new ExecutionOptions(timeout, Path.of(required(values, "--evidence-dir")));
    return new VerifierArguments(artifact, run, services, execution);
  }

  private static Map<String, String> parsePairs(String[] args) {
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
    return values;
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
            + "Usage: --artifact-path PATH --checksum-path PATH "
            + "--trading-day YYYY-MM-DD --account-id UUID "
            + "--run-id ID --evidence-dir PATH "
            + "[--risk-target HOST:PORT] "
            + "[--kafka-bootstrap HOST:PORT] "
            + "[--topic matching.commands] "
            + "[--timeout-seconds N]");
  }

  /** Mounted Market Reference artifact and checksum paths. */
  record ArtifactFiles(Path artifactPath, Path checksumPath) {
    ArtifactFiles {
      Objects.requireNonNull(artifactPath, "artifact path is required");
      Objects.requireNonNull(checksumPath, "checksum path is required");
    }
  }

  /** Runtime service endpoints used by the verifier. */
  record ServiceEndpoints(String riskTarget, String kafkaBootstrap, String topic) {
    private static final String DEFAULT_RISK_TARGET = "risk-service:50052";
    private static final String DEFAULT_KAFKA_BOOTSTRAP = "kafka:9092";
    private static final String DEFAULT_TOPIC = "matching.commands";

    ServiceEndpoints {
      requireText(riskTarget, "Risk target");
      requireText(kafkaBootstrap, "Kafka bootstrap");
      requireText(topic, "topic");
    }
  }

  /** One end-to-end timeout budget and the directory receiving evidence. */
  record ExecutionOptions(Duration timeout, Path evidenceDir) {
    ExecutionOptions {
      Objects.requireNonNull(timeout, "timeout is required");
      Objects.requireNonNull(evidenceDir, "evidence directory is required");
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("timeout must be positive");
      }
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}

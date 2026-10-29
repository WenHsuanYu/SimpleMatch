package com.simplematch.tools.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

class DependencyRecoveryVerifierTest {
  private final ObjectMapper mapper = new ObjectMapper();
  @TempDir Path directory;

  @Test
  void acceptsDurablePostgresRecovery() throws IOException {
    assertEquals(
        "local/postgresql/worker-stop",
        new DependencyRecoveryVerifier().verify("postgresql", fixture("postgresql")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"redis", "kafka"})
  void acceptsPortableRedisAndKafkaRecovery(String component) throws IOException {
    assertEquals(
        "local/" + component + "/worker-stop",
        new DependencyRecoveryVerifier().verify(component, fixture(component)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"postgresql", "redis", "kafka"})
  void distinguishesPodRestartFromWorkerLoss(String component) throws IOException {
    final Path report = fixture(component);
    final ObjectNode facts = (ObjectNode) mapper.readTree(report.toFile());
    facts.put("fault_mode", "pod-restart");
    facts.putNull("worker_stop");
    if (component.equals("redis")) {
      ((ObjectNode) facts.path("recovery")).put("rescheduled_after_worker_loss", false);
    }
    mapper.writeValue(report.toFile(), facts);

    assertEquals("local/" + component + "/pod-restart",
        new DependencyRecoveryVerifier().verify(component, report));
  }

  @ParameterizedTest
  @MethodSource("insufficientFacts")
  void rejectsFactsThatDoNotProveRecovery(Rejection rejection) throws IOException {
    final Path report = fixture(rejection.component());
    replace(report, rejection.pointer(), rejection.json());

    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> new DependencyRecoveryVerifier().verify(rejection.component(), report));
    assertTrue(failure.getMessage().contains(rejection.reason()), failure.getMessage());
  }

  static Stream<Rejection> insufficientFacts() {
    return Stream.of(
        new Rejection("postgresql", "/recovery/durable_after", "false", "durable_after"),
        new Rejection("postgresql", "/target/after/pvc", "\"other-volume\"", "pvc"),
        new Rejection("postgresql", "/worker_stop/container_id_after", "\"other-worker\"",
            "Worker container"),
        new Rejection("postgresql", "/recovery/owner_worker_replacement_absent", "false",
            "owner_worker_replacement_absent"),
        new Rejection("postgresql", "/schema_version", "1", "schema"),
        new Rejection("postgresql", "/failure_reason", "\"cleanup failed\"", "failure reason"),
        new Rejection("redis", "/target/after/node", "\"simplematch-live-worker2\"", "move"),
        new Rejection("redis", "/recovery/disposable_state", "false", "disposable_state"),
        new Rejection("kafka", "/marker/preserved_after", "false", "preserved_after"),
        new Rejection("kafka", "/quorum/isr_after", "2", "ISR"),
        new Rejection("kafka", "/quorum/available_during", "1", "quorum"),
        new Rejection("kafka", "/recovery/catch_up_probe", "\"ready-only\"", "offset-lag"),
        new Rejection("kafka", "/brokers_after/0/cluster_id", "\"other-cluster\"", "cluster_id"),
        new Rejection("kafka", "/brokers_after/2/node", "\"simplematch-live-worker2\"", "share"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"FAILED", "UNSUPPORTED", "BLOCKED"})
  void refusesNonSuccessfulRecovery(String status) throws IOException {
    final Path report = fixture("postgresql");
    replace(report, "/status", mapper.writeValueAsString(status));
    assertThrows(IllegalArgumentException.class,
        () -> new DependencyRecoveryVerifier().verify("postgresql", report));
  }

  @Test
  void derivesScopeWithoutAttestingCallerClaimText() throws IOException {
    final Path report = fixture("postgresql");
    replace(report, "/claim_boundary", "[\"production HA proved\"]");
    replace(report, "/target/before/worker_slot", "\"a\"");
    replace(report, "/target/after/worker_slot", "\"b\"");

    assertEquals("local/postgresql/worker-stop",
        new DependencyRecoveryVerifier().verify("postgresql", report));
  }

  @Test
  void rejectsContradictoryDuplicateStatusFields() throws IOException {
    final Path report = fixture("postgresql");
    final String original = Files.readString(report);
    Files.writeString(report, "{\"status\":\"FAILED\"," + original.substring(1));

    assertThrows(IOException.class,
        () -> new DependencyRecoveryVerifier().verify("postgresql", report));
  }

  @Test
  void commandLineUsesTheSameAcceptanceRule() throws Exception {
    final CommandResult result = cli("postgresql", fixture("postgresql").toString());
    assertEquals(0, result.exitCode(), result.output());
    assertEquals("local/postgresql/worker-stop", result.output().strip());
  }

  @Test
  void commandLineRejectsMalformedOrUnrelatedEvidence() throws Exception {
    final Path report = fixture("postgresql");
    final CommandResult unrelated = cli("redis", report.toString());
    assertEquals(1, unrelated.exitCode());
    assertTrue(unrelated.output().contains("Wrong report component"));
    Files.writeString(report, "{} {}");
    final CommandResult malformed = cli("postgresql", report.toString());
    assertEquals(1, malformed.exitCode());
    assertTrue(malformed.output().contains("Recovery evidence rejected:"));
  }

  private CommandResult cli(String... arguments) throws Exception {
    final List<String> parameters = new ArrayList<>(List.of("dependency"));
    parameters.addAll(List.of(arguments));
    return CommandResult.verifier(directory.resolve("command-output.txt"),
        parameters.toArray(String[]::new));
  }

  private void replace(Path report, String pointer, String json) throws IOException {
    final JsonNode facts = mapper.readTree(report.toFile());
    final int separator = pointer.lastIndexOf('/');
    final ObjectNode target = (ObjectNode) facts.at(pointer.substring(0, separator));
    target.set(pointer.substring(separator + 1), mapper.readTree(json));
    mapper.writeValue(report.toFile(), facts);
  }

  private record Rejection(String component, String pointer, String json, String reason) {}

  private Path fixture(String component) throws IOException {
    final Path report = directory.resolve(component + ".json");
    try (var source =
        Objects.requireNonNull(getClass().getResourceAsStream("/" + component + ".json"))) {
      Files.copy(source, report);
    }
    return report;
  }
}

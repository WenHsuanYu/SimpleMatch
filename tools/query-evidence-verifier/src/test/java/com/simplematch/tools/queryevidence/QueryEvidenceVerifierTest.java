package com.simplematch.tools.queryevidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class QueryEvidenceVerifierTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir Path directory;

  @Test
  void acceptsHealthyRetainedEvidence() throws IOException {
    writeHealthyEvidence();
    final JsonNode verdict = new QueryEvidenceVerifier().evaluate(directory);

    assertEquals("query-security-phase1/2", verdict.path("contractVersion").asText());
    assertEquals("PASS", verdict.path("status").asText());
    assertEquals("PROVEN", verdict.path("activeProcessingLiveness").path("status").asText());
    assertEquals(12, verdict.path("checks").size());
    verdict.path("checks").forEach(check -> assertTrue(check.path("passed").booleanValue()));
    assertEquals(
        List.of(
            "minimalBusinessEnvelopes",
            "deterministicRebuild",
            "postgresReadsDuringRedisOutage",
            "freshnessRestored",
            "marketReferenceIdentity",
            "queryServiceRestored",
            "activeProcessingLiveness",
            "criticalPathIsolationUnderQuiescence",
            "criticalPathIsolation",
            "deployedReadMatrix",
            "obsoleteCacheIgnored",
            "operatorCredentialSeparation"),
        Stream.of(verdict.path("checks"))
            .flatMap(node -> java.util.stream.StreamSupport.stream(node.spliterator(), false))
            .map(check -> check.path("name").asText())
            .toList());
  }

  @ParameterizedTest
  @MethodSource("rejections")
  void rejectsEvidenceThatDoesNotProveItsNamedCheck(Rejection rejection) throws IOException {
    writeHealthyEvidence();
    replace(rejection.file(), rejection.pointer(), rejection.value());

    final JsonNode verdict = new QueryEvidenceVerifier().evaluate(directory);

    assertEquals("FAIL", verdict.path("status").asText());
    assertFalse(checkPassed(verdict, rejection.check()), rejection.toString());
    if (rejection.check().equals("activeProcessingLiveness")) {
      assertEquals("NOT_PROVEN", verdict.at("/activeProcessingLiveness/status").asText());
    }
  }

  static Stream<Rejection> rejections() {
    return Stream.of(
        new Rejection("redis-outage", "/order/freshness", "{}", "minimalBusinessEnvelopes"),
        new Rejection(
            "rebuilt", "/executions/data/0/executionId", "\"other\"", "deterministicRebuild"),
        new Rejection("baseline", "/executions/data", "[]", "deterministicRebuild"),
        new Rejection(
            "redis-outage", "/order/data/state", "\"OTHER\"", "postgresReadsDuringRedisOutage"),
        new Rejection(
            "rebuilt",
            "/freshness/partitions/0/recoveryState",
            "\"REBUILDING\"",
            "freshnessRestored"),
        new Rejection(
            "rebuilt", "/marketReference/data/artifactId", "\"\"", "marketReferenceIdentity"),
        new Rejection("restoration", "/queryServiceReady", "false", "queryServiceRestored"),
        new Rejection("restoration", "/criticalConsumersReady", "false", "criticalPathIsolation"),
        new Rejection(
            "critical-during-query-outage",
            "/quickfixPendingIntents",
            "1",
            "criticalPathIsolation"),
        new Rejection("read-matrix", "/observations", "99", "deployedReadMatrix"),
        new Rejection("legacy-cache", "/unchangedAfterReads", "false", "obsoleteCacheIgnored"),
        new Rejection(
            "operator-boundary", "/observations/0/status", "200", "operatorCredentialSeparation"),
        isolation("/samples/1/sampleIndex", "0"),
        isolation("/samples/1/criticalPathHealth/paths/0/pods/0/uid", "\"replacement\""),
        isolation("/samples/1/matchingCommittedOffsets/partitions/0/committedOffset", "19"),
        active("/matchingEvent/eventType", "\"MATCHING_EVENT_TYPE_TRADE_EXECUTED\""),
        active("/fixSubmission/accountId", "\"other\""),
        active("/fixDeliveryIntent/status", "\"PENDING\""));
  }

  @Test
  void ignoresObservationTimestampsAndPostActiveConsumerChanges() throws IOException {
    writeHealthyEvidence();
    replace("rebuilt", "/order/data/updatedAtUnixMs", "999");
    replace("rebuilt", "/executions/data/0/executedAtUnixMs", "999");
    replace("rebuilt", "/accountSummary/data/updatedAtUnixMs", "999");
    replace("rebuilt", "/marketReference/data/updatedAtUnixMs", "999");
    Files.writeString(directory.resolve("critical-after.json"), "{\"postActiveEvent\":true}");

    assertEquals("PASS", new QueryEvidenceVerifier().evaluate(directory).path("status").asText());
  }

  @ParameterizedTest
  @ValueSource(strings = {"order", "executions"})
  void rejectsMalformedBusinessDataEvenWhenAllSnapshotsAgree(String view) throws IOException {
    writeHealthyEvidence();
    final String value = view.equals("executions") ? "[\"invalid\"]" : "\"invalid\"";
    for (String snapshot : List.of("baseline", "redis-outage", "rebuilt")) {
      replace(snapshot, "/" + view + "/data", value);
    }
    assertEquals("FAIL", new QueryEvidenceVerifier().evaluate(directory).path("status").asText());
  }

  @Test
  void reportsMissingActiveEvidenceAsNotProven() throws IOException {
    writeHealthyEvidence();
    Files.delete(directory.resolve("active-processing-liveness.json"));
    final JsonNode verdict = new QueryEvidenceVerifier().evaluate(directory);
    assertEquals("FAIL", verdict.path("status").asText());
    assertEquals("NOT_PROVEN", verdict.at("/activeProcessingLiveness/status").asText());
    assertFalse(checkPassed(verdict, "activeProcessingLiveness"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{} {}", "{\"a\":1,\"a\":2}"})
  void refusesAmbiguousEvidenceDocuments(String input) throws IOException {
    writeHealthyEvidence();
    Files.writeString(directory.resolve("baseline.json"), input);
    assertThrows(IOException.class, () -> new QueryEvidenceVerifier().evaluate(directory));
  }

  @Test
  void acceptsFifteenDistinctProbeSamples() throws IOException {
    writeHealthyEvidence();
    final ObjectNode probe = (ObjectNode) read("critical-query-isolation-probe");
    final ObjectNode sample = (ObjectNode) probe.at("/samples/0");
    final ArrayNode samples = mapper.createArrayNode();
    for (int index = 0; index < 15; index++) {
      samples.add(sample.deepCopy().put("sampleIndex", index));
    }
    probe.set("samples", samples);
    probe.put("sampleCount", 15).put("probeDurationSeconds", 15);
    mapper.writeValue(directory.resolve("critical-query-isolation-probe.json").toFile(), probe);
    assertEquals("PASS", new QueryEvidenceVerifier().evaluate(directory).path("status").asText());
  }

  @Test
  void commandWritesVerdictsAndNeverLeavesStalePassForInvalidEvidence() throws Exception {
    writeHealthyEvidence();
    final Path destination = directory.resolve("verdict.json");
    assertEquals(0, command(destination));
    assertEquals("PASS", mapper.readTree(destination.toFile()).path("status").asText());
    replace("restoration", "/queryServiceReady", "false");
    assertEquals(1, command(destination));
    assertEquals("FAIL", mapper.readTree(destination.toFile()).path("status").asText());
    Files.writeString(destination, "{\"status\":\"PASS\"}");
    Files.delete(directory.resolve("baseline.json"));
    assertEquals(2, command(destination));
    assertFalse(Files.exists(destination));
  }

  private int command(Path destination) throws Exception {
    final Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("queryVerifierClasspath"),
                QueryEvidenceVerifierMain.class.getName(),
                directory.toString(),
                destination.toString())
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("cli.log").toFile())
            .start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Verifier process exceeded its deadline");
      return process.exitValue();
    } finally {
      process.destroyForcibly();
    }
  }

  private void writeHealthyEvidence() throws IOException {
    try (var input = getClass().getResourceAsStream("/evidence-template.json")) {
      final ObjectNode fixture = (ObjectNode) mapper.readTree(input);
      fixture.set("critical-after", fixture.path("critical-before").deepCopy());
      fixture.set("critical-during-query-outage", fixture.path("critical-before").deepCopy());
      fixture.set("redis-outage", fixture.path("baseline").deepCopy());
      fixture.set("rebuilt", fixture.path("baseline").deepCopy());
      expandFleet(fixture);
      for (var file : fixture.properties()) {
        mapper.writeValue(directory.resolve(file.getKey() + ".json").toFile(), file.getValue());
      }
    }
  }

  /** Expands the fixture's one-Pod and one-sample examples into the declared scenario. */
  private void expandFleet(ObjectNode fixture) {
    final ObjectNode sample = (ObjectNode) fixture.at("/critical-query-isolation-probe/samples/0");
    final ObjectNode topology = (ObjectNode) sample.at("/criticalPathHealth/matchingTopology");
    final ArrayNode pods = (ArrayNode) topology.path("pods");
    final ObjectNode prototype = (ObjectNode) pods.get(0);
    pods.removeAll();
    for (int index = 0; index < 15; index++) {
      final String ordinal = Integer.toString(index);
      final ObjectNode pod =
          prototype
              .deepCopy()
              .put("name", "matching-" + ordinal)
              .put("uid", "matching-uid-" + ordinal)
              .put("ordinal", ordinal)
              .put("node", "worker-" + ordinal)
              .put("pvc", "matching-baseline-matching-" + ordinal)
              .put("pv", "pv-" + ordinal);
      pod.putArray("pvNodeAffinityNodes").add("worker-" + ordinal);
      pods.add(pod);
    }
    for (JsonNode path : sample.at("/criticalPathHealth/paths")) {
      if (path.path("path").asText().equals("matching")) {
        final ArrayNode readyPods = (ArrayNode) path.path("pods");
        final ObjectNode readyPrototype = (ObjectNode) readyPods.get(0);
        readyPods.removeAll();
        for (JsonNode pod : pods) {
          readyPods.add(
              readyPrototype
                  .deepCopy()
                  .put("name", pod.path("name").asText())
                  .put("uid", pod.path("uid").asText()));
        }
      }
    }
    ((ArrayNode) fixture.at("/critical-query-isolation-probe/samples"))
        .add(sample.deepCopy().put("sampleIndex", 1));
  }

  private JsonNode read(String file) throws IOException {
    return mapper.readTree(directory.resolve(file + ".json").toFile());
  }

  private void replace(String file, String pointer, String value) throws IOException {
    final JsonNode evidence = read(file);
    final int slash = pointer.lastIndexOf('/');
    final JsonNode parent = evidence.at(pointer.substring(0, slash));
    final String name = pointer.substring(slash + 1);
    if (parent.isArray()) {
      ((ArrayNode) parent).set(Integer.parseInt(name), mapper.readTree(value));
    } else {
      ((ObjectNode) parent).set(name, mapper.readTree(value));
    }
    mapper.writeValue(directory.resolve(file + ".json").toFile(), evidence);
  }

  private boolean checkPassed(JsonNode verdict, String name) {
    for (JsonNode check : verdict.path("checks")) {
      if (check.path("name").asText().equals(name)) {
        return check.path("passed").booleanValue();
      }
    }
    throw new AssertionError("Missing verdict check: " + name);
  }

  private static Rejection active(String pointer, String value) {
    return new Rejection("active-processing-liveness", pointer, value, "activeProcessingLiveness");
  }

  private static Rejection isolation(String pointer, String value) {
    return new Rejection(
        "critical-query-isolation-probe", pointer, value, "criticalPathIsolationUnderQuiescence");
  }

  private record Rejection(String file, String pointer, String value, String check) {}
}

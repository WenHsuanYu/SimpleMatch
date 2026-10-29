package com.simplematch.tools.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MarketDataEvidenceVerifierTest {
  private final ObjectMapper mapper = new ObjectMapper();
  @TempDir Path directory;

  @Test
  void commandPublishesTheSevenCheckVerdict() throws Exception {
    fixture();

    final CommandResult result = evaluate();

    assertEquals(0, result.exitCode(), result.output());
    final JsonNode verdict = mapper.readTree(directory.resolve("verdict.json").toFile());
    assertEquals("PASS", verdict.path("status").asText());
    assertEquals(List.of("criticalConsumerIsolation", "deterministicRebuild", "redisRepair",
        "projectionRestored", "streamerRestored", "matchingIsolation", "criticalConsumersRestored"),
        verdict.path("checks").findValuesAsText("name"));
    for (JsonNode check : verdict.path("checks")) {
      assertTrue(check.path("passed").booleanValue(), check.toString());
    }
  }

  @ParameterizedTest
  @MethodSource("failedChecks")
  void eachNecessaryConditionFailsItsNamedCheck(Rejection rejection) throws Exception {
    fixture();
    final Path input = directory.resolve(rejection.file() + ".json");
    final ObjectNode changed = (ObjectNode) mapper.readTree(input.toFile());
    changed.set(rejection.field(), mapper.readTree(rejection.value()));
    mapper.writeValue(input.toFile(), changed);

    assertEquals(1, evaluate().exitCode());
    final JsonNode verdict = mapper.readTree(directory.resolve("verdict.json").toFile());
    assertEquals("FAIL", verdict.path("status").asText());
    for (JsonNode check : verdict.path("checks")) {
      assertEquals(!check.path("name").asText().equals(rejection.check()),
          check.path("passed").booleanValue(), check.toString());
    }
  }

  static Stream<Rejection> failedChecks() {
    return Stream.of(
        new Rejection("critical-after", "persistenceProgress", "[]", "criticalConsumerIsolation"),
        new Rejection("rebuilt-snapshot", "sourceKafkaOffset", "10", "deterministicRebuild"),
        new Rejection("restoration", "redisRepaired", "false", "redisRepair"),
        new Rejection("restoration", "projectionReady", "false", "projectionRestored"),
        new Rejection("restoration", "streamerReady", "false", "streamerRestored"),
        new Rejection("restoration", "matchingReady", "14", "matchingIsolation"),
        new Rejection("restoration", "criticalConsumersReady", "false", "criticalConsumersRestored"));
  }

  @Test
  void missingSnapshotFactsCannotProveAnEqualRebuild() throws Exception {
    fixture();
    for (String name : List.of("baseline-snapshot", "rebuilt-snapshot")) {
      final Path path = directory.resolve(name + ".json");
      final ObjectNode snapshot = (ObjectNode) mapper.readTree(path.toFile());
      snapshot.remove("sourceKafkaOffset");
      mapper.writeValue(path.toFile(), snapshot);
    }

    assertEquals(1, evaluate().exitCode());
  }

  @Test
  void emptyConsumerObjectsCannotProveIsolation() throws Exception {
    fixture();
    for (String name : List.of("critical-before", "critical-after")) {
      Files.writeString(directory.resolve(name + ".json"), "{}");
    }

    assertEquals(1, evaluate().exitCode());
  }

  @Test
  void equalIncompleteSnapshotsStillFailTheRebuild() throws Exception {
    fixture();
    for (String name : List.of("baseline-snapshot", "rebuilt-snapshot")) {
      final Path path = directory.resolve(name + ".json");
      final ObjectNode snapshot = (ObjectNode) mapper.readTree(path.toFile());
      snapshot.put("completeSnapshot", false);
      mapper.writeValue(path.toFile(), snapshot);
    }

    assertEquals(1, evaluate().exitCode());
    final JsonNode verdict = mapper.readTree(directory.resolve("verdict.json").toFile());
    assertEquals("FAIL", verdict.path("status").asText());
  }

  @ParameterizedTest
  @ValueSource(strings = {"critical-before", "critical-after", "baseline-snapshot",
      "rebuilt-snapshot", "restoration"})
  void eachInputIsRequiredAndInvalidInputCannotRetainPass(String input) throws Exception {
    fixture();
    assertEquals(0, evaluate().exitCode());
    Files.delete(directory.resolve(input + ".json"));

    assertEquals(1, evaluate().exitCode());
    assertFalse(Files.exists(directory.resolve("verdict.json")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "{} {}", "{\"matchingReady\":15,\"matchingReady\":14}", "broken"})
  void unreadableRestorationCannotLeavePass(String invalid) throws Exception {
    fixture();
    assertEquals(0, evaluate().exitCode());
    Files.writeString(directory.resolve("restoration.json"), invalid);

    assertEquals(1, evaluate().exitCode());
    assertFalse(Files.exists(directory.resolve("verdict.json")));
  }

  @Test
  void numericRepresentationsAndObservationTimesDoNotChangeBusinessEquality() throws Exception {
    fixture();
    final Path rebuilt = directory.resolve("rebuilt-snapshot.json");
    final ObjectNode snapshot = (ObjectNode) mapper.readTree(rebuilt.toFile());
    snapshot.put("instrumentSequence", 1.0);
    snapshot.put("observedAt", "later-observation");
    mapper.writeValue(rebuilt.toFile(), snapshot);
    final Path restoration = directory.resolve("restoration.json");
    final ObjectNode restored = (ObjectNode) mapper.readTree(restoration.toFile());
    restored.put("matchingReady", 15.0);
    mapper.writeValue(restoration.toFile(), restored);

    assertEquals(0, evaluate().exitCode());
  }

  @Test
  void destinationCannotOverwriteInputAndWriteFailureCannotReportSuccess() throws Exception {
    fixture();
    final Path input = directory.resolve("baseline-snapshot.json");
    final String original = Files.readString(input);
    final CommandResult overwrite = CommandResult.verifier(directory.resolve("overwrite.txt"),
        "market-data", directory.toString(), input.toString());
    assertEquals(1, overwrite.exitCode());
    assertEquals(original, Files.readString(input));

    final CommandResult unwritable = CommandResult.verifier(directory.resolve("unwritable.txt"),
        "market-data", directory.toString(), directory.resolve("absent/verdict.json").toString());
    assertEquals(1, unwritable.exitCode());
  }

  private void fixture() throws Exception {
    final JsonNode snapshot = mapper.readTree("""
        {"sourceMatchingEventId":"event-1","venueMic":"XTAI","symbol":"1101",
         "instrumentSequence":1,"sourcePartitionId":0,"sourceKafkaOffset":9,
         "completeSnapshot":true,"lastTrade":null,
         "bids":[{"priceUnits":100000,"quantityShares":100}],"asks":[]}
        """);
    final JsonNode critical = mapper.readTree("""
        {"persistenceProgress":[{"partition_id":0,"last_processed_offset":9}],
         "accountProgress":[],"quickfixProgress":[]}
        """);
    for (String name : List.of("baseline-snapshot", "rebuilt-snapshot")) {
      mapper.writeValue(directory.resolve(name + ".json").toFile(), snapshot);
    }
    for (String name : List.of("critical-before", "critical-after")) {
      mapper.writeValue(directory.resolve(name + ".json").toFile(), critical);
    }
    mapper.writeValue(directory.resolve("restoration.json").toFile(), mapper.readTree("""
        {"redisRepaired":true,"projectionReady":true,"streamerReady":true,
         "matchingReady":15,"criticalConsumersReady":true}
        """));
  }

  private CommandResult evaluate() throws Exception {
    return CommandResult.verifier(directory.resolve("command.txt"), "market-data",
        directory.toString(), directory.resolve("verdict.json").toString());
  }

  private record Rejection(String file, String field, String value, String check) {}
}

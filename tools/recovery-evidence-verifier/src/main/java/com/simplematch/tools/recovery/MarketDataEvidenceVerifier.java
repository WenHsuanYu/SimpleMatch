package com.simplematch.tools.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Evaluates the seven retained Market Data rebuild and restoration checks. */
final class MarketDataEvidenceVerifier {
  private static final List<String> INPUT_FILES = List.of(
      "critical-before.json", "critical-after.json", "baseline-snapshot.json",
      "rebuilt-snapshot.json", "restoration.json");
  private static final List<String> SNAPSHOT_FIELDS = List.of(
      "sourceMatchingEventId", "venueMic", "symbol", "instrumentSequence", "sourcePartitionId",
      "sourceKafkaOffset", "completeSnapshot", "lastTrade", "bids", "asks");

  /** Removes old output before reading evidence and writes the current seven-check verdict. */
  String write(Path directory, Path destination) throws IOException {
    final Path destinationName = destination.getFileName();
    if (destinationName == null || INPUT_FILES.contains(destinationName.toString())) {
      throw new IllegalArgumentException("Verdict destination must not replace an evidence input.");
    }
    Files.deleteIfExists(destination);
    final EvidenceFiles files = new EvidenceFiles(directory);
    final JsonNode before = files.json("critical-before.json");
    final JsonNode after = files.json("critical-after.json");
    final JsonNode baseline = files.json("baseline-snapshot.json");
    final JsonNode rebuilt = files.json("rebuilt-snapshot.json");
    final JsonNode restoration = files.json("restoration.json");
    final ArrayNode checks = JsonReports.object().putArray("checks");
    add(checks, "criticalConsumerIsolation", consumerState(before) && consumerState(after)
        && equal(before, after));
    add(checks, "deterministicRebuild", equal(businessView(baseline), businessView(rebuilt))
        && baseline.path("completeSnapshot").isBoolean()
        && baseline.path("completeSnapshot").booleanValue()
        && rebuilt.path("completeSnapshot").isBoolean()
        && rebuilt.path("completeSnapshot").booleanValue());
    add(checks, "redisRepair", flag(restoration, "redisRepaired"));
    add(checks, "projectionRestored", flag(restoration, "projectionReady"));
    add(checks, "streamerRestored", flag(restoration, "streamerReady"));
    add(checks, "matchingIsolation", restoration.path("matchingReady").isNumber()
        && equal(restoration.path("matchingReady"), IntNode.valueOf(15)));
    add(checks, "criticalConsumersRestored", flag(restoration, "criticalConsumersReady"));
    final ObjectNode verdict = JsonReports.object();
    boolean passed = true;
    for (JsonNode check : checks) {
      passed &= check.path("passed").booleanValue();
    }
    verdict.put("status", passed ? "PASS" : "FAIL");
    verdict.set("checks", checks);
    JsonReports.write(destination, verdict);
    ReportFields.require(passed, "Market Data checks failed: " + checks);
    return "local/market-data/rebuild";
  }

  private static boolean flag(JsonNode value, String field) {
    return value.path(field).isBoolean() && value.path(field).booleanValue();
  }

  private static boolean consumerState(JsonNode state) {
    return List.of("persistenceProgress", "accountProgress", "quickfixProgress").stream()
        .allMatch(field -> state.path(field).isArray());
  }

  private static ObjectNode businessView(JsonNode snapshot) {
    ReportFields.require(SNAPSHOT_FIELDS.stream().allMatch(snapshot::has),
        "Missing Market Data snapshot fields.");
    final ObjectNode result = JsonReports.object();
    for (String field : SNAPSHOT_FIELDS) {
      result.set(field, snapshot.path(field));
    }
    return result;
  }

  private static boolean equal(JsonNode left, JsonNode right) {
    return left.equals((first, second) -> {
      if (first.isNumber() && second.isNumber()) {
        return first.decimalValue().compareTo(second.decimalValue());
      }
      return first.equals(second) ? 0 : 1;
    }, right);
  }

  private static void add(ArrayNode checks, String name, boolean passed) {
    checks.addObject().put("name", name).put("passed", passed);
  }
}

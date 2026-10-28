package com.simplematch.tools.queryevidence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates retained Query evidence without a database, network, or deployment operation. */
public final class QueryEvidenceVerifier {
  private static final List<String> REQUIRED_FILES =
      List.of(
          "critical-before",
          "critical-during-query-outage",
          "query-outage",
          "critical-query-isolation-probe",
          "baseline",
          "redis-outage",
          "rebuilt",
          "restoration",
          "read-matrix",
          "legacy-cache",
          "operator-boundary");
  private static final List<String> BUSINESS_VIEWS =
      List.of("order", "executions", "accountSummary", "marketReference");
  private final ObjectMapper mapper =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  /**
   * Reads the evidence directory and returns the twelve-check verdict.
   *
   * @param directory directory containing the retained JSON evidence files
   * @return the verdict, including each named check and active-processing status
   * @throws IOException if required evidence is absent or JSON input is malformed
   */
  public JsonNode evaluate(Path directory) throws IOException {
    final Map<String, JsonNode> evidence = readEvidence(directory);
    final JsonNode baseline = evidence.get("baseline");
    final JsonNode outage = evidence.get("redis-outage");
    final JsonNode rebuilt = evidence.get("rebuilt");
    final JsonNode restoration = evidence.get("restoration");
    final JsonNode active = evidence.get("active-processing-liveness");
    final boolean activePassed =
        new ActiveProcessingEvidence().isHealthy(active, evidence.get("query-outage"));
    final List<JsonNode> snapshots = List.of(baseline, outage, rebuilt);
    final ArrayNode checks = mapper.createArrayNode();
    add(checks, "minimalBusinessEnvelopes", snapshots.stream().allMatch(this::minimalEnvelopes));
    add(
        checks,
        "deterministicRebuild",
        JsonChecks.equal(businessView(baseline), businessView(rebuilt))
            && baseline.at("/executions/data").isArray()
            && !baseline.at("/executions/data").isEmpty());
    add(
        checks,
        "postgresReadsDuringRedisOutage",
        JsonChecks.equal(businessView(baseline), businessView(outage)));
    add(checks, "freshnessRestored", snapshots.stream().allMatch(this::healthyFreshness));
    add(
        checks,
        "marketReferenceIdentity",
        rebuilt.at("/marketReference/data/tradingDay").isTextual()
            && rebuilt.at("/marketReference/data/tradingDay").textValue().length() == 10
            && JsonChecks.nonemptyText(rebuilt.at("/marketReference/data/artifactId")));
    add(
        checks,
        "queryServiceRestored",
        restoration.path("queryServiceReady").equals(BooleanNode.TRUE));
    add(checks, "activeProcessingLiveness", activePassed);
    add(
        checks,
        "criticalPathIsolationUnderQuiescence",
        new QuiescentIsolationEvidence()
            .isHealthy(
                evidence.get("critical-query-isolation-probe"), evidence.get("critical-before")));
    add(
        checks,
        "criticalPathIsolation",
        JsonChecks.equal(
                evidence.get("critical-before"), evidence.get("critical-during-query-outage"))
            && restoration.path("queryOutageObserved").equals(BooleanNode.TRUE)
            && JsonChecks.number(restoration.path("matchingReady"), 15)
            && restoration.path("criticalConsumersReady").equals(BooleanNode.TRUE));
    addSecurityChecks(checks, evidence);
    final boolean passed =
        JsonChecks.elements(checks).stream().allMatch(check -> check.path("passed").booleanValue());
    final ObjectNode verdict = mapper.createObjectNode();
    verdict.put("contractVersion", "query-security-phase1/2");
    verdict.put("status", passed ? "PASS" : "FAIL");
    verdict
        .putObject("activeProcessingLiveness")
        .put("status", activePassed ? "PROVEN" : "NOT_PROVEN")
        .put(
            "reason",
            activePassed
                ? "a public IOC FIX order was admitted and processed while query-service had zero"
                    + " Pods"
                : "active processing evidence is missing or does not prove the public IOC event"
                    + " path");
    verdict.set("checks", checks);
    return verdict;
  }

  private Map<String, JsonNode> readEvidence(Path directory) throws IOException {
    final Map<String, JsonNode> evidence = new HashMap<>();
    for (String name : REQUIRED_FILES) {
      evidence.put(name, readObject(directory.resolve(name + ".json")));
    }
    final Path active = directory.resolve("active-processing-liveness.json");
    evidence.put(
        "active-processing-liveness",
        Files.exists(active) && Files.size(active) > 0
            ? readObject(active)
            : mapper.createObjectNode());
    return evidence;
  }

  private JsonNode readObject(Path path) throws IOException {
    final JsonNode value = mapper.readTree(Files.readAllBytes(path));
    if (value == null || !value.isObject()) {
      throw new IOException("Evidence must contain one JSON object: " + path.getFileName());
    }
    return value;
  }

  private boolean minimalEnvelopes(JsonNode snapshot) {
    return BUSINESS_VIEWS.stream()
        .allMatch(
            name -> {
              final JsonNode envelope = snapshot.path(name);
              final JsonNode freshness = envelope.path("freshness");
              final JsonNode data = envelope.path("data");
              final boolean validData =
                  name.equals("executions")
                      ? data.isArray()
                          && JsonChecks.elements(data).stream().allMatch(JsonNode::isObject)
                      : data.isObject();
              return JsonChecks.fields(envelope, Set.of("data", "freshness"))
                  && validData
                  && JsonChecks.fields(freshness, Set.of("projectionState"))
                  && Set.of("UNKNOWN", "RECOVERY_REQUIRED", "NO_GAP_RECORDED")
                      .contains(freshness.path("projectionState").asText());
            });
  }

  private JsonNode businessView(JsonNode snapshot) {
    final ObjectNode result = mapper.createObjectNode();
    for (String name : BUSINESS_VIEWS) {
      final JsonNode data = snapshot.path(name).path("data").deepCopy();
      if (data.isObject()) {
        ((ObjectNode) data).remove("updatedAtUnixMs");
      } else if (data.isArray() && name.equals("executions")) {
        JsonChecks.elements(data).stream()
            .filter(JsonNode::isObject)
            .forEach(item -> ((ObjectNode) item).remove("executedAtUnixMs"));
      }
      result.set(name, data);
    }
    return result;
  }

  private boolean healthyFreshness(JsonNode snapshot) {
    final List<JsonNode> partitions = JsonChecks.elements(snapshot.at("/freshness/partitions"));
    return !partitions.isEmpty()
        && partitions.stream()
            .map(partition -> partition.path("sourceTopic").asText())
            .collect(java.util.stream.Collectors.toSet())
            .equals(Set.of("account.lifecycle", "matching.events"))
        && partitions.stream()
            .allMatch(
                partition ->
                    JsonChecks.number(
                            partition.path("lastProcessedOffset"), value -> value.signum() >= 0)
                        && JsonChecks.text(partition.path("recoveryState"), "READY"));
  }

  private void addSecurityChecks(ArrayNode checks, Map<String, JsonNode> evidence) {
    final JsonNode matrix = evidence.get("read-matrix");
    final JsonNode cache = evidence.get("legacy-cache");
    final JsonNode boundary = evidence.get("operator-boundary");
    add(
        checks,
        "deployedReadMatrix",
        JsonChecks.text(matrix.path("status"), "PASS")
            && JsonChecks.number(
                matrix.path("replicas"), value -> value.compareTo(java.math.BigDecimal.TWO) >= 0)
            && JsonChecks.number(
                matrix.path("observations"),
                value -> value.compareTo(java.math.BigDecimal.valueOf(100)) >= 0));
    add(
        checks,
        "obsoleteCacheIgnored",
        JsonChecks.text(cache.path("status"), "PASS")
            && JsonChecks.number(cache.path("seededEntries"), 4)
            && cache.path("unchangedAfterReads").equals(BooleanNode.TRUE));
    add(
        checks,
        "operatorCredentialSeparation",
        JsonChecks.text(boundary.path("status"), "PASS")
            && JsonChecks.elements(boundary.path("observations")).size() == 9
            && JsonChecks.elements(boundary.path("observations")).stream()
                .allMatch(observation -> JsonChecks.number(observation.path("status"), 401)));
  }

  private void add(ArrayNode checks, String name, boolean passed) {
    checks.addObject().put("name", name).put("passed", passed);
  }
}

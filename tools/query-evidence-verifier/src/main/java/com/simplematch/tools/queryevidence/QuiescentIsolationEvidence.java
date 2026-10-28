package com.simplematch.tools.queryevidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Validates repeated critical-path observations while Query has no Pods. */
final class QuiescentIsolationEvidence {
  private static final Map<String, String> PATH_RESOURCES =
      Map.of(
          "account",
          "deployment/account-service",
          "admission",
          "deployment/risk-service",
          "marketData",
          "deployment/market-data-projection",
          "matching",
          "statefulset/matching",
          "persistence",
          "deployment/persistence",
          "quickfix",
          "statefulset/quickfix-gateway",
          "reservation",
          "deployment/account-service");

  boolean isHealthy(JsonNode probe, JsonNode expectedState) {
    final List<JsonNode> samples = JsonChecks.elements(probe.path("samples"));
    return validTiming(probe)
        && JsonChecks.number(probe.path("sampleCount"), samples.size())
        && validIndexes(samples)
        && samples.stream().allMatch(sample -> healthySample(sample, expectedState))
        && stablePaths(samples)
        && nondecreasingOffsets(samples);
  }

  private boolean validTiming(JsonNode probe) {
    final JsonNode start = probe.path("probeStartedEpochMs");
    final JsonNode end = probe.path("probeCompletedEpochMs");
    return JsonChecks.number(
            probe.path("probeDurationSeconds"), value -> value.compareTo(BigDecimal.TWO) >= 0)
        && JsonChecks.equal(probe.path("sampleCount"), probe.path("probeDurationSeconds"))
        && JsonChecks.number(start, value -> value.signum() >= 0)
        && JsonChecks.number(end, value -> value.compareTo(start.decimalValue()) >= 0)
        && JsonChecks.number(
            probe.path("elapsedMilliseconds"),
            value -> value.compareTo(end.decimalValue().subtract(start.decimalValue())) == 0)
        && JsonChecks.number(
            probe.path("commandTimeoutSeconds"),
            value -> value.signum() > 0 && value.compareTo(BigDecimal.valueOf(30)) <= 0);
  }

  private boolean validIndexes(List<JsonNode> samples) {
    final Set<BigDecimal> actual =
        samples.stream()
            .map(sample -> sample.path("sampleIndex"))
            .filter(JsonNode::isNumber)
            .map(index -> index.decimalValue().stripTrailingZeros())
            .collect(Collectors.toSet());
    return actual.equals(
        IntStream.range(0, samples.size())
            .mapToObj(index -> BigDecimal.valueOf(index).stripTrailingZeros())
            .collect(Collectors.toSet()));
  }

  private boolean healthySample(JsonNode sample, JsonNode expectedState) {
    final JsonNode positions = sample.path("matchingCommittedOffsets");
    final List<JsonNode> partitions = JsonChecks.elements(positions.path("partitions"));
    return JsonChecks.values(
            sample,
            Map.of("/queryPodCount", 0, "/matchingReady", 15, "/criticalConsumersReady", true))
        && JsonChecks.equal(sample.path("consumerState"), expectedState)
        && JsonChecks.text(positions.path("topic"), "matching.commands")
        && partitions.size() == 15
        && IntStream.range(0, 15)
            .allMatch(
                index ->
                    JsonChecks.number(partitions.get(index).path("partition"), index)
                        && JsonChecks.number(
                            partitions.get(index).path("committedOffset"),
                            value -> value.signum() >= 0))
        && healthyPaths(sample.path("criticalPathHealth"));
  }

  private boolean healthyPaths(JsonNode health) {
    final List<JsonNode> paths = JsonChecks.elements(health.path("paths"));
    return paths.size() == PATH_RESOURCES.size()
        && paths.stream()
            .map(path -> path.path("path").asText())
            .collect(Collectors.toSet())
            .equals(PATH_RESOURCES.keySet())
        && paths.stream().allMatch(this::healthyPath)
        && new MatchingTopologyEvidence().isHealthy(health.path("matchingTopology"));
  }

  private boolean healthyPath(JsonNode path) {
    final JsonNode desired = path.path("desiredReplicas");
    final List<JsonNode> pods = JsonChecks.elements(path.path("pods"));
    return JsonChecks.text(path.path("resource"), PATH_RESOURCES.get(path.path("path").asText()))
        && JsonChecks.number(desired, value -> value.signum() > 0)
        && (!JsonChecks.text(path.path("path"), "matching") || JsonChecks.number(desired, 15))
        && List.of("readyReplicas", "podCount", "readyPodCount").stream()
            .allMatch(field -> JsonChecks.equal(path.path(field), desired))
        && JsonChecks.number(desired, pods.size())
        && pods.stream()
            .allMatch(
                pod ->
                    JsonChecks.text(pod.path("phase"), "Running")
                        && pod.path("ready").equals(BooleanNode.TRUE)
                        && JsonChecks.nonemptyText(pod.path("uid"))
                        && JsonChecks.number(
                            pod.path("restartCount"), value -> value.signum() >= 0));
  }

  private boolean stablePaths(List<JsonNode> samples) {
    final List<JsonNode> baseline =
        JsonChecks.elements(samples.getFirst().at("/criticalPathHealth/paths"));
    return samples.stream()
        .skip(1)
        .allMatch(
            sample ->
                baseline.stream()
                    .allMatch(
                        path -> {
                          final JsonNode current =
                              JsonChecks.elements(sample.at("/criticalPathHealth/paths")).stream()
                                  .filter(
                                      candidate ->
                                          JsonChecks.equal(
                                              candidate.path("path"), path.path("path")))
                                  .findFirst()
                                  .orElseThrow();
                          return JsonChecks.equal(
                                  current.path("restartCount"), path.path("restartCount"))
                              && podUids(current).equals(podUids(path));
                        }));
  }

  private List<String> podUids(JsonNode path) {
    return JsonChecks.elements(path.path("pods")).stream()
        .map(pod -> pod.path("uid").asText())
        .sorted()
        .toList();
  }

  private boolean nondecreasingOffsets(List<JsonNode> samples) {
    return IntStream.range(1, samples.size())
        .allMatch(
            index ->
                IntStream.range(0, 15)
                    .allMatch(
                        partition -> {
                          final String pointer =
                              "/matchingCommittedOffsets/partitions/"
                                  + partition
                                  + "/committedOffset";
                          return samples
                                  .get(index - 1)
                                  .at(pointer)
                                  .decimalValue()
                                  .compareTo(samples.get(index).at(pointer).decimalValue())
                              <= 0;
                        }));
  }
}

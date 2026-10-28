package com.simplematch.tools.queryevidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Checks the declared fifteen-partition fleet and its Pod, revision, and storage identities. */
final class MatchingTopologyEvidence {
  boolean isHealthy(JsonNode topology) {
    final JsonNode statefulset = topology.path("statefulset");
    final List<JsonNode> pods = JsonChecks.elements(topology.path("pods"));
    return healthyStatefulSet(statefulset)
        && pods.size() == 15
        && expectedIdentities(topology, pods, statefulset.path("name").asText())
        && pods.stream().allMatch(pod -> healthyPod(pod, statefulset))
        && topology.path("unownedMatchingPods").isArray()
        && topology.path("unownedMatchingPods").isEmpty()
        && topology.path("unexpectedMatchingPods").isArray()
        && topology.path("unexpectedMatchingPods").isEmpty();
  }

  private boolean healthyStatefulSet(JsonNode statefulset) {
    return JsonChecks.nonemptyText(statefulset.path("name"))
        && JsonChecks.nonemptyText(statefulset.path("uid"))
        && JsonChecks.number(statefulset.path("desiredReplicas"), 15)
        && JsonChecks.number(statefulset.path("readyReplicas"), 15)
        && JsonChecks.nonemptyText(statefulset.path("currentRevision"))
        && JsonChecks.equal(
            statefulset.path("currentRevision"), statefulset.path("updateRevision"));
  }

  private boolean expectedIdentities(JsonNode topology, List<JsonNode> pods, String name) {
    final List<String> ordinals = IntStream.range(0, 15).mapToObj(Integer::toString).toList();
    final Set<String> expectedNames =
        ordinals.stream().map(ordinal -> name + "-" + ordinal).collect(Collectors.toSet());
    return JsonChecks.elements(topology.path("expectedOrdinals")).stream()
            .map(JsonNode::textValue)
            .toList()
            .equals(ordinals)
        && pods.stream()
            .map(pod -> pod.path("name").asText())
            .collect(Collectors.toSet())
            .equals(expectedNames)
        && pods.stream()
            .map(pod -> pod.path("ordinal").asText())
            .collect(Collectors.toSet())
            .equals(Set.copyOf(ordinals))
        && pods.stream().map(pod -> pod.path("uid").asText()).distinct().count() == 15;
  }

  private boolean healthyPod(JsonNode pod, JsonNode statefulset) {
    return pod.path("ready").equals(BooleanNode.TRUE)
        && JsonChecks.nonemptyText(pod.path("ordinal"))
        && JsonChecks.nonemptyText(pod.path("uid"))
        && JsonChecks.nonemptyText(pod.path("node"))
        && pod.path("ownerStatefulSet").equals(BooleanNode.TRUE)
        && JsonChecks.equal(pod.path("controllerRevisionHash"), statefulset.path("currentRevision"))
        && JsonChecks.text(
            pod.path("pvc"),
            "matching-baseline-"
                + statefulset.path("name").asText()
                + "-"
                + pod.path("ordinal").asText())
        && healthyStorage(pod);
  }

  private boolean healthyStorage(JsonNode pod) {
    return JsonChecks.text(pod.path("pvcPhase"), "Bound")
        && JsonChecks.elements(pod.path("pvcAccessModes")).stream()
            .anyMatch(mode -> JsonChecks.text(mode, "ReadWriteOncePod"))
        && JsonChecks.nonemptyText(pod.path("pv"))
        && JsonChecks.elements(pod.path("pvNodeAffinityNodes")).stream()
            .anyMatch(node -> JsonChecks.equal(node, pod.path("node")));
  }
}

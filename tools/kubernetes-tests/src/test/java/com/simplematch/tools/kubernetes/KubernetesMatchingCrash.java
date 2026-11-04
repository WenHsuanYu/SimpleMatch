package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

/** Verifies a real SIGKILL restart before the next public FIX order is submitted. */
final class KubernetesMatchingCrash {
  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final ObjectMapper json = new ObjectMapper();

  KubernetesMatchingCrash(KubernetesBusinessEnvironment.PublicOrder context) {
    this.context = context;
  }

  /** Requires the completed cancel's input offset and one bounded, observed restart. */
  void verify(int partition, long inputOffset) throws Exception {
    if (inputOffset < 0) {
      throw new IllegalArgumentException("Matching crash requires an observed source input offset");
    }
    final var target = context.target();
    final var budget = OperationBudget.start(Duration.ofMinutes(2));
    final String podName = "matching-" + partition;
    final var before = target.client().read("get", "pod", podName, "-o", "json");
    assertEquals(target.run().runId(), before.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    final var oldClaim = claim(before);
    final String oldContainerId = matchingContainer(before).path("containerID").asText();
    assertTrue(oldContainerId.matches("containerd://[0-9a-f]{64}"));
    KubernetesMatchingPause.crash(context, partition,
        before.path("metadata").path("uid").asText(), oldContainerId);

    JsonNode after;
    do {
      budget.remaining();
      after = target.client().find("get", "pod", podName, "--ignore-not-found", "-o", "json")
          .orElseGet(json::createObjectNode);
      if (crashRestarted(before, after)) {
        break;
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(500)));
    } while (true);

    final var newClaim = claim(after);
    assertEquals(oldClaim.path("metadata").path("uid"), newClaim.path("metadata").path("uid"));
    assertEquals(oldClaim.path("spec").path("volumeName"),
        newClaim.path("spec").path("volumeName"));
    save("matching-crash-owner.json", Map.of(
        "podUid", after.path("metadata").path("uid").asText(),
        "node", after.path("spec").path("nodeName").asText(),
        "oldContainerId", oldContainerId,
        "newContainerId", matchingContainer(after).path("containerID").asText(),
        "restartCount", matchingContainer(after).path("restartCount").asInt(),
        "lastExitCode", matchingContainer(after).path("lastState").path("terminated")
            .path("exitCode").asInt()));

    final String ownerUid = after.path("metadata").path("uid").asText();
    while (true) {
      budget.remaining();
      final var runtime = target.matchingRuntime(after);
      budget.remaining();
      if ("READY".equals(runtime.path("runtime_state").asText())
          && "OPEN".equals(runtime.path("partition_state").asText())
          && runtime.path("admission").path("ownership_permitted").asBoolean()
          && runtime.path("admission").path("recovery_complete").asBoolean()
          && runtime.path("highest_contiguous_completed_offset").asLong(-1) >= inputOffset) {
        assertEquals(podName + ":" + ownerUid,
            runtime.path("admission").path("owner_id").asText());
        assertEquals(partition, runtime.path("admission").path("partition_id").asInt());
        save("matching-crash-runtime.json", runtime);
        return;
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(500)));
    }
  }

  /** Accepts only a same-Pod restart with a new container and exit code 137. */
  static boolean crashRestarted(JsonNode before, JsonNode after) {
    final var previous = matchingContainer(before);
    final var current = matchingContainer(after);
    final String previousContainer = previous.path("containerID").asText();
    final String currentContainer = current.path("containerID").asText();
    final String uid = before.path("metadata").path("uid").asText();
    final String node = before.path("spec").path("nodeName").asText();
    final boolean ready = java.util.stream.StreamSupport.stream(
        after.path("status").path("conditions").spliterator(), false)
        .anyMatch(value -> "Ready".equals(value.path("type").asText())
            && "True".equals(value.path("status").asText()));
    return !uid.isBlank() && uid.equals(after.path("metadata").path("uid").asText())
        && !node.isBlank() && node.equals(after.path("spec").path("nodeName").asText())
        && previousContainer.matches("containerd://[0-9a-f]{64}")
        && currentContainer.matches("containerd://[0-9a-f]{64}")
        && !previousContainer.equals(currentContainer)
        && previous.path("restartCount").isIntegralNumber()
        && current.path("restartCount").asInt(-1) == previous.path("restartCount").asInt() + 1
        && current.path("lastState").path("terminated").path("exitCode").asInt(-1) == 137
        && ready;
  }

  private static JsonNode matchingContainer(JsonNode pod) {
    final var matching = java.util.stream.StreamSupport.stream(
        pod.path("status").path("containerStatuses").spliterator(), false)
        .filter(value -> "matching".equals(value.path("name").asText())).toList();
    return matching.size() == 1 ? matching.getFirst() : MissingNode.getInstance();
  }

  private JsonNode claim(JsonNode pod) throws Exception {
    final String claimName = java.util.stream.StreamSupport.stream(
        pod.path("spec").path("volumes").spliterator(), false)
        .filter(value -> "matching-baseline".equals(value.path("name").asText()))
        .map(value -> value.path("persistentVolumeClaim").path("claimName").asText())
        .findFirst().orElseThrow();
    return context.target().client().read("get", "pvc", claimName, "-o", "json");
  }

  private void save(String name, Object value) throws Exception {
    Files.write(context.target().run().evidence().resolve(name), json.writeValueAsBytes(value));
  }
}

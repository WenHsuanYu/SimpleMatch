package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Checks that normal Pod replacement cannot pass as an abrupt process crash. */
final class KubernetesMatchingCrashTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void requiresSamePodNewContainerAndSigkillExit() throws Exception {
    final var before = json.readTree("""
        {"metadata":{"uid":"original"},"spec":{"nodeName":"worker-1"},
         "status":{"containerStatuses":[{"name":"matching","restartCount":0,
           "containerID":"containerd://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}}
        """);
    final var after = json.readTree("""
        {"metadata":{"uid":"original"},"spec":{"nodeName":"worker-1"},
         "status":{"conditions":[{"type":"Ready","status":"True"}],
           "containerStatuses":[{"name":"matching","restartCount":1,
             "containerID":"containerd://bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
             "lastState":{"terminated":{"exitCode":137}}}]}}
        """);

    assertTrue(KubernetesMatchingCrash.crashRestarted(before, after));

    final var differentPod = after.deepCopy();
    ((ObjectNode) differentPod.path("metadata")).put("uid", "replacement");
    assertFalse(KubernetesMatchingCrash.crashRestarted(before, differentPod));

    final var normalExit = after.deepCopy();
    ((ObjectNode) normalExit.path("status").path("containerStatuses").get(0)
        .path("lastState").path("terminated")).put("exitCode", 0);
    assertFalse(KubernetesMatchingCrash.crashRestarted(before, normalExit));

    final var sameContainer = after.deepCopy();
    ((ObjectNode) sameContainer.path("status").path("containerStatuses").get(0))
        .put("containerID", before.path("status").path("containerStatuses").get(0)
            .path("containerID").asText());
    assertFalse(KubernetesMatchingCrash.crashRestarted(before, sameContainer));

    final var unrelatedRestart = after.deepCopy();
    ((ObjectNode) unrelatedRestart.path("status").path("containerStatuses").get(0))
        .put("restartCount", 2);
    assertFalse(KubernetesMatchingCrash.crashRestarted(before, unrelatedRestart));

    final var notReady = after.deepCopy();
    ((ObjectNode) notReady.path("status").path("conditions").get(0)).put("status", "False");
    assertFalse(KubernetesMatchingCrash.crashRestarted(before, notReady));
  }
}

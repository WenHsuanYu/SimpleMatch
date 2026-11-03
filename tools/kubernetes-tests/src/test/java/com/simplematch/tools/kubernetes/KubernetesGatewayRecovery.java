package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/** Replaces one Gateway while retaining the real client endpoint, session store, and data claim. */
final class KubernetesGatewayRecovery {
  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final Path fix;
  private final Future<?> client;
  private final long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
  private final ObjectMapper json = new ObjectMapper();

  KubernetesGatewayRecovery(KubernetesBusinessEnvironment.PublicOrder context, Path fix,
      Future<?> client) {
    this.context = context;
    this.fix = fix;
    this.client = client;
  }

  KubernetesPortForward replace(KubernetesPortForward original) throws Exception {
    final var target = context.target();
    final var before = owner();
    target.client().run("", "delete", "pod", target.gatewayPod(), "--wait=false");
    JsonNode after;
    do {
      remaining();
      after = owner();
      if (!before.path("podUid").equals(after.path("podUid")) && after.path("ready").asBoolean()) {
        break;
      }
      Thread.sleep(500);
    } while (true);
    Files.writeString(target.run().evidence().resolve("gateway-recovery-owner.json"),
        json.writeValueAsString(Map.of("before", before, "after", after)));
    assertNotEquals(before.path("podUid"), after.path("podUid"));
    assertEquals(before.path("claimUid"), after.path("claimUid"));
    assertEquals(before.path("volume"), after.path("volume"));
    original.close();
    final var replacement = target.client().forward("pod/" + target.gatewayPod(), 5001,
        fix.resolve("recovery-forward.log"), original.port());
    try {
      try (var management = target.client().forward("pod/" + target.gatewayPod(), 8080,
          fix.resolve("recovery-management-forward.log"))) {
        context.admission().open(management.port(), remaining());
      }
      Files.writeString(fix.resolve("recovery-release.next"),
          Long.toString(System.currentTimeMillis() + remaining().toMillis()));
      Files.move(fix.resolve("recovery-release.next"), fix.resolve("recovery-release"),
          StandardCopyOption.ATOMIC_MOVE);
      while (!Files.isRegularFile(fix.resolve("recovery.json"))) {
        remaining();
        if (client.isDone()) {
          client.get();
          throw new IllegalStateException("FIX client stopped before recovery completed");
        }
        Thread.sleep(100);
      }
      return replacement;
    } catch (Exception failure) {
      try {
        replacement.close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private JsonNode owner() throws Exception {
    final var target = context.target();
    final var pod = target.client().find("get", "pod", "quickfix-gateway-0",
        "--ignore-not-found", "-o", "json").orElseGet(json::createObjectNode);
    if (!pod.has("metadata")) {
      return json.createObjectNode();
    }
    final String claim = java.util.stream.StreamSupport.stream(
        pod.path("spec").path("volumes").spliterator(), false)
        .filter(volume -> "quickfix-data".equals(volume.path("name").asText()))
        .map(volume -> volume.path("persistentVolumeClaim").path("claimName").asText())
        .findFirst().orElseThrow();
    final var pvc = target.client().read("get", "pvc", claim, "-o", "json");
    final boolean ready = java.util.stream.StreamSupport.stream(
        pod.path("status").path("conditions").spliterator(), false)
        .anyMatch(condition -> "Ready".equals(condition.path("type").asText())
            && "True".equals(condition.path("status").asText()));
    return json.valueToTree(Map.of("podUid", pod.path("metadata").path("uid").asText(),
        "claimUid", pvc.path("metadata").path("uid").asText(),
        "volume", pvc.path("spec").path("volumeName").asText(), "ready", ready));
  }

  private Duration remaining() throws TimeoutException {
    final long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new TimeoutException("Gateway recovery exceeded three minutes");
    }
    return Duration.ofNanos(nanos);
  }
}

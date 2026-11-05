package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

/** Requires Kubernetes readiness and stable control-plane operations. */
final class KindHealth {
  private KindHealth() {}

  static void requireStable(KubernetesClient client, Path evidence, String phase) throws Exception {
    client.requireControlPlane(evidence.resolve("control-" + phase));
  }

  static void requireReady(JsonNode object) {
    final boolean ready = java.util.stream.StreamSupport.stream(
        object.path("status").path("conditions").spliterator(), false).anyMatch(condition ->
        "Ready".equals(condition.path("type").asText())
            && "True".equals(condition.path("status").asText()));
    if (!ready) {
      throw new IllegalStateException("Kubernetes object is not Ready: "
          + object.path("metadata").path("name").asText());
    }
  }
}

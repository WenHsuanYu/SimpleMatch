package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Deletes only the originally observed namespace under atomic API preconditions. */
record OwnedNamespace(KubernetesClient client, String name, String uid, String runId) {
  static final String OWNER_LABEL = "simplematch.io/verification-run";

  void requestDeletion() throws Exception {
    final var current = client.find("get", "namespace", name, "--ignore-not-found", "-o", "json");
    if (current.isEmpty()) {
      return;
    }
    final var metadata = current.orElseThrow().path("metadata");
    if (!uid.equals(metadata.path("uid").asText())
        || !runId.equals(metadata.path("labels").path(OWNER_LABEL).asText())) {
      throw new IllegalStateException("namespace identity or owner changed; refusing deletion");
    }
    final var options = Map.of("apiVersion", "v1", "kind", "DeleteOptions",
        "propagationPolicy", "Foreground", "preconditions", Map.of("uid", uid,
            "resourceVersion", metadata.path("resourceVersion").asText()));
    client.run(new ObjectMapper().writeValueAsString(options), "delete", "--raw",
        "/api/v1/namespaces/" + name, "-f", "-");
  }
}

package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Carries the validated scenario endpoint without coupling its probes to cluster creation. */
record KubernetesScenarioTarget(KubernetesClient client, String namespace,
    VerificationContext run, Map<String, String> replacements) {
  KubernetesScenarioTarget {
    replacements = Map.copyOf(replacements);
  }

  void create(JsonNode document) throws Exception {
    client.create(KubernetesDocuments.relocate(document, namespace, run.runId(), replacements));
  }

  String gatewayPod() throws Exception {
    final var pods = client.read("get", "pods", "-l",
        "app.kubernetes.io/name=quickfix-gateway," + OwnedNamespace.OWNER_LABEL + "="
            + run.runId(), "-o", "json").path("items");
    if (pods.size() != 1 || pods.get(0).path("metadata").path("uid").asText().isBlank()) {
      throw new IllegalStateException("run-owned Gateway Pod identity is ambiguous or absent");
    }
    return pods.get(0).path("metadata").path("name").asText();
  }
}

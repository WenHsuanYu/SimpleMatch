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
}

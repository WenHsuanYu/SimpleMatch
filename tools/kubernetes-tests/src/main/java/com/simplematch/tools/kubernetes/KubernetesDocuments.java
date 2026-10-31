package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** Applies run facts to canonical documents while preserving their workload policy. */
final class KubernetesDocuments {
  private KubernetesDocuments() {}

  static ObjectNode relocate(JsonNode original, String namespace, String runId,
      Map<String, String> images) {
    final ObjectNode document = original.deepCopy();
    document.withObject("metadata").put("namespace", namespace)
        .withObject("labels").put(OwnedNamespace.OWNER_LABEL, runId);
    if ("RoleBinding".equals(document.path("kind").asText())) {
      for (var subject : document.path("subjects")) {
        ((ObjectNode) subject).put("namespace", namespace);
      }
    }
    if (document.path("spec").path("template").isObject()) {
      document.withObject("spec").withObject("template").withObject("metadata")
          .withObject("labels").put(OwnedNamespace.OWNER_LABEL, runId);
    }
    replaceImages(document, images);
    return document;
  }

  private static void replaceImages(JsonNode document, Map<String, String> images) {
    if (document.isObject() && document.has("image")) {
      final String current = document.path("image").asText();
      final String replacement = images.get(current);
      if (replacement != null) {
        ((ObjectNode) document).put("image", replacement).put("imagePullPolicy", "Never");
      }
    }
    for (JsonNode child : document) {
      replaceImages(child, images);
    }
  }

  static ObjectNode data(String kind, String name, Map<String, String> values) {
    return new ObjectMapper().valueToTree(Map.of("apiVersion", "v1", "kind", kind,
        "metadata", Map.of("name", name), "immutable", true,
        "Secret".equals(kind) ? "stringData" : "data", values));
  }
}

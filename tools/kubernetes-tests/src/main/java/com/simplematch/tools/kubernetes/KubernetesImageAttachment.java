package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Validates a containerd subject attachment before excluding it as non-runnable content. */
final class KubernetesImageAttachment {
  private KubernetesImageAttachment() {}

  static boolean hasSubject(JsonNode descriptor) {
    return descriptor.path("annotations").has("io.containerd.manifest.subject");
  }

  static void requireSubject(JsonNode descriptor, Map<String, String> candidates) {
    final String subject = descriptor.path("annotations")
        .path("io.containerd.manifest.subject").asText();
    if (!KubernetesImageIndex.MANIFEST_TYPES.contains(descriptor.path("mediaType").asText())
        || !unlabelledPlatform(descriptor) || !candidates.containsKey(subject)) {
      throw new IllegalStateException("containerd attachment has no verified runnable subject");
    }
  }

  static void requireManifest(JsonNode descriptor, JsonNode manifest) {
    KubernetesImageIndex.requireSchema(manifest, KubernetesImageIndex.MANIFEST_TYPES);
    if (!descriptor.path("mediaType").asText().equals(manifest.path("mediaType").asText())
        || manifest.has("manifests")) {
      throw new IllegalStateException("containerd attachment has an invalid manifest");
    }
  }

  static void requireUnknownConfig(JsonNode config) {
    if (!"unknown".equals(config.path("os").asText())
        || !"unknown".equals(config.path("architecture").asText())) {
      throw new IllegalStateException("containerd attachment has a runnable image config");
    }
  }

  private static boolean unlabelledPlatform(JsonNode descriptor) {
    final String os = descriptor.path("platform").path("os").asText();
    final String architecture = descriptor.path("platform").path("architecture").asText();
    return (os.isBlank() || "unknown".equals(os))
        && (architecture.isBlank() || "unknown".equals(architecture));
  }
}

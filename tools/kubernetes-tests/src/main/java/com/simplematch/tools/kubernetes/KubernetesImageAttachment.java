package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Validates a containerd subject attachment before excluding it as non-runnable content. */
final class KubernetesImageAttachment {
  private KubernetesImageAttachment() {}

  static boolean hasSubject(JsonNode descriptor) {
    return descriptor.path("annotations").has("io.containerd.manifest.subject")
        || "attestation-manifest".equals(descriptor.path("annotations")
            .path("vnd.docker.reference.type").asText());
  }

  static void requireSubject(JsonNode descriptor, Map<String, String> candidates) {
    final String subject = referencedSubject(descriptor);
    if (!KubernetesImageIndex.MANIFEST_TYPES.contains(descriptor.path("mediaType").asText())
        || !unlabelledPlatform(descriptor) || !candidates.containsKey(subject)) {
      throw new IllegalStateException("containerd attachment has no verified runnable subject");
    }
  }

  private static String referencedSubject(JsonNode descriptor) {
    final var annotations = descriptor.path("annotations");
    final boolean containerd = annotations.has("io.containerd.manifest.subject");
    final boolean docker = "attestation-manifest".equals(
        annotations.path("vnd.docker.reference.type").asText());
    if (containerd && docker) {
      throw new IllegalStateException("image attachment has two conflicting subject markers");
    }
    return annotations.path(containerd ? "io.containerd.manifest.subject"
        : "vnd.docker.reference.digest").asText();
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

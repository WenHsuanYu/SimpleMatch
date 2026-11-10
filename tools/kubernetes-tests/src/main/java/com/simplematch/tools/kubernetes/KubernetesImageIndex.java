package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Resolves runnable image manifests from SHA-verified OCI or Docker content. */
final class KubernetesImageIndex {
  private static final int MAX_DEPTH = 6;
  private static final int MAX_DOCUMENTS = 64;
  private static final Set<String> INDEX_TYPES = Set.of(
      "application/vnd.oci.image.index.v1+json",
      "application/vnd.docker.distribution.manifest.list.v2+json");
  private static final Set<String> MANIFEST_TYPES = Set.of(
      "application/vnd.oci.image.manifest.v1+json",
      "application/vnd.docker.distribution.manifest.v2+json");
  private static final Set<String> CONFIG_TYPES = Set.of(
      "application/vnd.oci.image.config.v1+json",
      "application/vnd.docker.container.image.v1+json");

  private KubernetesImageIndex() {}

  /** Rejects zero or multiple distinct runnable linux/amd64 manifests. */
  static String resolveConfig(String digest, ContentReader reader) throws Exception {
    final var candidates = new Traversal(reader).visit(digest, null, false, 0);
    if (candidates.size() != 1) {
      throw new IllegalStateException("workload index has " + candidates.size()
          + " distinct runnable linux/amd64 manifests");
    }
    return candidates.values().iterator().next();
  }

  private static final class Traversal {
    private final ContentReader reader;
    private final Set<String> path = new HashSet<>();
    private int visited;

    private Traversal(ContentReader reader) {
      this.reader = java.util.Objects.requireNonNull(reader);
    }

    private Map<String, String> visit(String digest, String expectedMediaType,
        boolean requiredAmd64, int depth) throws Exception {
      if (!path.add(digest)) {
        throw new IllegalStateException("workload image index contains a cycle");
      }
      try {
        final JsonNode document = load(digest, depth);
        final String actualType = document.path("mediaType").asText();
        if (expectedMediaType != null && !expectedMediaType.equals(actualType)) {
          throw new IllegalStateException("workload descriptor differs from content media type");
        }
        if (document.has("manifests")) {
          return visitIndex(document, requiredAmd64, depth);
        }
        return visitManifest(digest, document, requiredAmd64, depth);
      } finally {
        path.remove(digest);
      }
    }

    private JsonNode load(String digest, int depth) throws Exception {
      if (depth > MAX_DEPTH || ++visited > MAX_DOCUMENTS) {
        throw new IllegalStateException("workload image index exceeds bounded traversal");
      }
      if (!digest.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalStateException("workload has no valid immutable content identity");
      }
      final JsonNode document = reader.read(digest);
      if (document == null || !document.isObject()) {
        throw new IllegalStateException("workload image content is not a JSON object");
      }
      return document;
    }

    private Map<String, String> visitIndex(JsonNode index, boolean requiredAmd64, int depth)
        throws Exception {
      requireSchema(index, INDEX_TYPES);
      if (!index.path("manifests").isArray() || index.path("manifests").isEmpty()) {
        throw new IllegalStateException("workload index has no valid manifest descriptors");
      }
      final Map<String, String> candidates = new LinkedHashMap<>();
      for (var descriptor : index.path("manifests")) {
        candidates.putAll(visitDescriptor(descriptor, requiredAmd64, depth));
      }
      return candidates;
    }

    private Map<String, String> visitDescriptor(JsonNode descriptor, boolean requiredAmd64,
        int depth) throws Exception {
      final String type = descriptor.path("mediaType").asText();
      requireDescriptorType(type);
      if (isAttestation(descriptor) || !selectedPlatform(descriptor)) {
        return Map.of();
      }
      final boolean branchRequired = requiredAmd64 || declaredAmd64(descriptor);
      final var branch = visit(descriptor.path("digest").asText(), type, branchRequired, depth + 1);
      if (branchRequired && branch.isEmpty()) {
        throw new IllegalStateException("linux/amd64 descriptor has no runnable manifest");
      }
      return branch;
    }

    private Map<String, String> visitManifest(String digest, JsonNode manifest,
        boolean requiredAmd64, int depth) throws Exception {
      requireSchema(manifest, MANIFEST_TYPES);
      if ("application/vnd.docker.attestation.manifest.v1+json".equals(
          manifest.path("artifactType").asText())) {
        if (requiredAmd64) {
          throw new IllegalStateException("runnable image manifest is an attestation");
        }
        return Map.of();
      }
      final JsonNode config = loadImageConfig(manifest, depth);
      if (!validatedAmd64(config, requiredAmd64)) {
        return Map.of();
      }
      return Map.of(digest, manifest.path("config").path("digest").asText());
    }

    private JsonNode loadImageConfig(JsonNode manifest, int depth) throws Exception {
      final JsonNode descriptor = manifest.path("config");
      if (!CONFIG_TYPES.contains(descriptor.path("mediaType").asText())) {
        throw new IllegalStateException("unsupported workload image config type");
      }
      return load(descriptor.path("digest").asText(), depth + 1);
    }

    private static boolean validatedAmd64(JsonNode config, boolean requiredAmd64) {
      final String os = config.path("os").asText();
      final String architecture = config.path("architecture").asText();
      if (!knownPlatform(os) || !knownPlatform(architecture)) {
        throw new IllegalStateException("workload image config has no known platform");
      }
      final boolean linuxAmd64 = "linux".equals(os) && "amd64".equals(architecture);
      if (requiredAmd64 && !linuxAmd64) {
        throw new IllegalStateException("workload descriptor differs from image config platform");
      }
      return linuxAmd64;
    }
  }

  private static void requireDescriptorType(String type) {
    if (!INDEX_TYPES.contains(type) && !MANIFEST_TYPES.contains(type)) {
      throw new IllegalStateException("unsupported workload index descriptor type");
    }
  }

  private static boolean knownPlatform(String value) {
    return !value.isBlank() && !"unknown".equals(value);
  }

  private static void requireSchema(JsonNode document, Set<String> types) {
    if (document.path("schemaVersion").asInt(-1) != 2
        || !types.contains(document.path("mediaType").asText())) {
      throw new IllegalStateException("unsupported workload image content schema");
    }
  }

  private static boolean isAttestation(JsonNode descriptor) {
    if (!"attestation-manifest".equals(descriptor.path("annotations")
        .path("vnd.docker.reference.type").asText())) {
      return false;
    }
    if (!MANIFEST_TYPES.contains(descriptor.path("mediaType").asText())
        || declaredAmd64(descriptor)) {
      throw new IllegalStateException("runnable workload image marked as attestation");
    }
    return true;
  }

  private static boolean selectedPlatform(JsonNode descriptor) {
    final String os = descriptor.path("platform").path("os").asText();
    final String architecture = descriptor.path("platform").path("architecture").asText();
    return (os.isBlank() || "linux".equals(os) || "unknown".equals(os))
        && (architecture.isBlank() || "amd64".equals(architecture)
            || "unknown".equals(architecture));
  }

  private static boolean declaredAmd64(JsonNode descriptor) {
    return "linux".equals(descriptor.path("platform").path("os").asText())
        && "amd64".equals(descriptor.path("platform").path("architecture").asText());
  }

  /** The production reader checks SHA-256 of every fetched content document. */
  @FunctionalInterface
  interface ContentReader {
    JsonNode read(String digest) throws Exception;
  }
}

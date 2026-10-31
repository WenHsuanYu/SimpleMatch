package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Validates current ownership data without compatibility inference from missing observations. */
final class KubernetesOwnershipReader {
  private KubernetesOwnershipReader() {}

  static KubernetesOwnership read(Path receipt, Path coordination) throws Exception {
    final var document = new ObjectMapper().readTree(receipt.toFile());
    if (document.path("schema_version").asInt() != 3) {
      throw new IllegalArgumentException("unsupported ownership schema");
    }
    final String runId = text(document, "run_id");
    if (!UUID.fromString(runId).toString().equals(runId)) {
      throw new IllegalArgumentException("cleanup requires a canonical run UUID");
    }
    final KindMode mode = KindMode.parse(text(document, "mode"));
    final Path config = coordination.resolve("run-" + runId).resolve("kubeconfig")
        .toAbsolutePath().normalize();
    if (!KindMode.namespace(runId).equals(text(document, "namespace"))
        || !mode.cluster(runId).equals(text(document, "cluster"))
        || !config.equals(Path.of(text(document, "kubeconfig")).toAbsolutePath().normalize())) {
      throw new IllegalArgumentException("cleanup target differs from the original run identity");
    }
    final var docker = new DockerIdentity(new DockerContext(text(document, "docker_context")),
        text(document, "daemon_id"));
    final var cluster = new KubernetesOwnership.Cluster(mode.cluster(runId),
        text(document, "cluster_uid"), bool(document, "cluster_created"),
        bool(document, "cluster_prepared"), identities(document.required("node_ids")),
        identities(document.required("node_volumes")));
    return new KubernetesOwnership(runId, docker, cluster, KindMode.namespace(runId),
        text(document, "namespace_uid"), mode, config);
  }

  private static List<String> identities(JsonNode values) {
    if (!values.isArray()) {
      throw new IllegalArgumentException("ownership IDs must be an array");
    }
    final var result = new ArrayList<String>();
    for (var value : values) {
      if (!value.isTextual() || !value.asText().matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("ownership contains an invalid Docker ID");
      }
      result.add(value.asText());
    }
    return List.copyOf(result);
  }

  private static String text(JsonNode document, String key) {
    final var value = document.required(key);
    if (!value.isTextual()) {
      throw new IllegalArgumentException("ownership field must be text: " + key);
    }
    return value.asText();
  }

  private static boolean bool(JsonNode document, String key) {
    final var value = document.required(key);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException("ownership creation state must be boolean: " + key);
    }
    return value.asBoolean();
  }
}

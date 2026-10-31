package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Claims one fresh namespace and waits for its namespace and bound disposable PVs to disappear. */
final class KubernetesNamespace {
  private final KubernetesClient client;
  private final String name;
  private final String runId;
  private final String clusterUid;
  private String uid = "";
  private boolean attempted;

  KubernetesNamespace(KubernetesClient client, String name, String runId, String clusterUid) {
    this.client = client;
    this.name = name;
    this.runId = runId;
    this.clusterUid = clusterUid;
  }

  void create() throws Exception {
    attempted = true;
    client.create(new ObjectMapper().valueToTree(Map.of("apiVersion", "v1", "kind", "Namespace",
        "metadata", Map.of("name", name, "labels", Map.of(OwnedNamespace.OWNER_LABEL, runId)))));
    uid = observedOwner().orElseThrow(
        () -> new IllegalStateException("created namespace is absent")).path("uid").asText();
  }

  static KubernetesNamespace retained(KubernetesClient client, KubernetesOwnership owner) {
    final var namespace = new KubernetesNamespace(
        client, owner.namespace(), owner.runId(), owner.cluster().uid());
    namespace.uid = owner.namespaceUid();
    namespace.attempted = true;
    return namespace;
  }

  private Optional<JsonNode> observedOwner() throws Exception {
    final var current = client.find("get", "namespace", name, "--ignore-not-found", "-o", "json");
    if (current.isEmpty()) {
      return Optional.empty();
    }
    final var metadata = current.orElseThrow().path("metadata");
    if (!runId.equals(metadata.path("labels").path(OwnedNamespace.OWNER_LABEL).asText())
        || (!uid.isEmpty() && !uid.equals(metadata.path("uid").asText()))) {
      throw new IllegalStateException("namespace belongs to another identity; refusing adoption");
    }
    return Optional.of(metadata);
  }

  void cleanup(java.nio.file.Path evidence) throws Exception {
    if (!attempted) {
      return;
    }
    final String observed = client.read("get", "namespace", "kube-system", "-o", "json")
        .path("metadata").path("uid").asText();
    if (!clusterUid.equals(observed)) {
      throw new IllegalStateException("cluster identity changed; refusing namespace cleanup");
    }
    final var metadata = observedOwner();
    final List<String> volumes = new ArrayList<>();
    for (var volume : client.read("get", "pv", "-o", "json").path("items")) {
      if (name.equals(volume.path("spec").path("claimRef").path("namespace").asText())) {
        volumes.add(volume.path("metadata").path("name").asText());
      }
    }
    if (metadata.isPresent()) {
      KindHealth.requireStable(client, evidence, "before-cleanup");
      new OwnedNamespace(client, name, metadata.orElseThrow().path("uid").asText(), runId)
          .requestDeletion();
      client.execute("", Duration.ofMinutes(4), "wait", "--for=delete", "namespace/" + name,
          "--timeout=180s").checked();
    }
    for (String volume : volumes) {
      client.execute("", Duration.ofMinutes(4), "wait", "--for=delete", "pv/" + volume,
          "--timeout=180s").checked();
    }
  }

  String uid() {
    return uid;
  }
}

package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks the concrete four-node Docker/Kubernetes binding before namespace mutation. */
final class KindTopology {
  private KindTopology() {}

  static void preflight(KubernetesSettings settings, BoundedCommand commands) throws Exception {
    OwnedKindCluster.requireNoAbandoned(commands, settings.dockerContext());
    if (settings.mode() == KindMode.DEDICATED) {
      final String cluster = settings.mode().cluster("");
      final String clusters = commands.run(List.of("kind", "get", "clusters"), "",
          Duration.ofSeconds(20)).checked();
      if (clusters.lines().anyMatch(cluster::equals)) {
        requireExisting(settings, commands, cluster);
      }
    }
  }

  private static void requireExisting(KubernetesSettings settings, BoundedCommand commands,
      String cluster) throws Exception {
    if (Files.isSymbolicLink(settings.coordination())) {
      throw new IllegalArgumentException("coordination directory must not be a symlink");
    }
    Files.createDirectories(settings.coordination());
    final var config = Files.createTempFile(settings.coordination(), "preflight-", ".kubeconfig");
    try {
      Files.writeString(config, commands.run(List.of("kind", "get", "kubeconfig", "--name",
          cluster), "", Duration.ofSeconds(20)).checked());
      final var client = new KubernetesClient(
          settings.root(), config, "kind-" + cluster, "default");
      final var nodes = OwnedKindCluster.inspect(commands, settings.dockerContext(), cluster);
      validate(client, nodes, cluster);
      KubernetesOwnership.requireReusable(settings, nodes,
          client.read("get", "namespace", "kube-system", "-o", "json")
              .path("metadata").path("uid").asText());
      KindHealth.requireImageHeadroom(commands, settings.dockerContext(), client);
    } finally {
      Files.deleteIfExists(config);
    }
  }

  static void validate(KubernetesClient client, List<JsonNode> nodes, String cluster)
      throws Exception {
    final var observed = client.read("get", "nodes", "-o", "json").path("items");
    if (nodes.size() != 4 || observed.size() != 4) {
      throw new IllegalStateException(
          "kind requires one control plane and three canonical workers");
    }
    final Map<String, String> roles = new HashMap<>();
    for (var node : nodes) {
      final String name = node.path("Name").asText().replaceFirst("^/", "");
      final String role = node.path("Config").path("Labels").path("io.x-k8s.kind.role").asText();
      if (roles.put(name, role) != null) {
        throw new IllegalStateException("duplicate Docker node identity");
      }
    }
    requireMapping(observed, roles, cluster);
    client.run("", "get", "--raw", "/readyz?verbose");
    if (!client.read("get", "namespaces", "-l", OwnedNamespace.OWNER_LABEL, "-o", "json")
        .path("items").isEmpty()) {
      throw new IllegalStateException("retained verification namespace requires explicit cleanup");
    }
  }

  private static void requireMapping(JsonNode observed, Map<String, String> roles, String cluster) {
    final Set<String> slots = new HashSet<>();
    final Set<String> names = new HashSet<>();
    int controls = 0;
    for (var node : observed) {
      final String name = node.path("metadata").path("name").asText();
      requireBinding(node, roles, cluster, names);
      final var labels = node.path("metadata").path("labels");
      if ("control-plane".equals(roles.get(name))) {
        controls++;
        requireControl(labels);
      } else {
        requireWorker(labels, roles.get(name), slots);
      }
      KindHealth.requireReady(node);
    }
    if (controls != 1 || !slots.equals(Set.of("0", "1", "2")) || !names.equals(roles.keySet())) {
      throw new IllegalStateException(
          "kind role/worker-slot mapping differs from the canonical profile");
    }
  }

  private static void requireBinding(JsonNode node, Map<String, String> roles, String cluster,
      Set<String> names) {
    final String name = node.path("metadata").path("name").asText();
    if (!names.add(name) || !roles.containsKey(name)
        || !("kind://docker/" + cluster + "/" + name)
            .equals(node.path("spec").path("providerID").asText())) {
      throw new IllegalStateException("Kubernetes node does not bind to its Docker node: " + name);
    }
  }

  private static void requireControl(JsonNode labels) {
    if (!labels.has("node-role.kubernetes.io/control-plane")
        || !"control-plane".equals(labels.path("simplematch.io/node-pool").asText())) {
      throw new IllegalStateException("control-plane role differs from canonical configuration");
    }
  }

  private static void requireWorker(JsonNode labels, String role, Set<String> slots) {
    if (!"worker".equals(role) || labels.has("node-role.kubernetes.io/control-plane")
        || !"local-resilience".equals(labels.path("simplematch.io/node-pool").asText())
        || !slots.add(labels.path("simplematch.io/worker-slot").asText())) {
      throw new IllegalStateException(
          "worker role or unique slot differs from canonical configuration");
    }
  }
}

package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Removes only run-created node identities with their original marker and daemon binding. */
final class OwnedKindCluster {
  private final BoundedCommand commands;
  private final DockerContext context;
  private final KubernetesOwnership.Cluster cluster;
  private final Path kubeconfig;
  private final String runId;

  OwnedKindCluster(BoundedCommand commands, DockerContext context,
      KubernetesOwnership.Cluster cluster, Path kubeconfig, String runId) {
    if (!cluster.created() || !kubeconfig.isAbsolute() || !kubeconfig.endsWith("kubeconfig")) {
      throw new IllegalArgumentException("cluster cleanup requires explicit run-created ownership");
    }
    this.commands = commands;
    this.context = context;
    this.cluster = cluster;
    this.kubeconfig = kubeconfig;
    this.runId = runId;
  }

  static List<JsonNode> inspect(BoundedCommand commands, DockerContext context, String cluster)
      throws Exception {
    final String ids = commands.run(context.command("ps", "-aq", "--no-trunc", "--filter",
        "label=io.x-k8s.kind.cluster=" + cluster), "", Duration.ofSeconds(30)).checked();
    final var result = new ArrayList<JsonNode>();
    for (String id : ids.lines().toList()) {
      result.add(new ObjectMapper().readTree(commands.run(context.command("inspect", id), "",
          Duration.ofSeconds(30)).checked()).get(0));
    }
    return List.copyOf(result);
  }

  static List<String> volumes(List<JsonNode> nodes) {
    final var names = new java.util.TreeSet<String>();
    for (var node : nodes) {
      for (var mount : node.path("Mounts")) {
        if ("volume".equals(mount.path("Type").asText())) {
          names.add(mount.path("Name").asText());
        }
      }
    }
    return List.copyOf(names);
  }

  static void requireNoAbandoned(BoundedCommand commands, DockerContext context) throws Exception {
    final String ids = commands.run(context.command("ps", "-aq", "--no-trunc", "--filter",
        "label=io.x-k8s.kind.cluster"), "", Duration.ofSeconds(30)).checked();
    for (String id : ids.lines().toList()) {
      final var node = new ObjectMapper().readTree(commands.run(context.command("inspect", id), "",
          Duration.ofSeconds(20)).checked()).get(0);
      final String name = node.path("Config").path("Labels").path("io.x-k8s.kind.cluster").asText();
      if (name.startsWith("simplematch-s3-")) {
        throw new IllegalStateException("existing disposable run requires explicit cleanup: "
            + name + ", node " + id + "; use that run's ownership.json");
      }
    }
  }

  boolean isGone() throws Exception {
    if (!inspect(commands, context, cluster.name()).isEmpty()) {
      return false;
    }
    requireVolumesGone(cluster.volumes());
    return true;
  }

  void delete() throws Exception {
    final var current = inspect(commands, context, cluster.name());
    if (current.isEmpty()) {
      requireVolumesGone(cluster.volumes());
      return;
    }
    final Path marker = kubeconfig.resolveSibling("owner");
    if (Files.isSymbolicLink(marker) || !runId.equals(Files.readString(marker))) {
      throw new IllegalStateException("kind ownership marker changed; refusing deletion");
    }
    requireOwnedNodes(current, marker);
    if (KindMode.DEDICATED.cluster("").equals(cluster.name())) {
      final Path privateDirectory = Objects.requireNonNull(
          kubeconfig.getParent(), "cleanup requires a private kubeconfig directory");
      final Path coordination = Objects.requireNonNull(
          privateDirectory.getParent(), "cleanup requires its original coordination directory");
      KubernetesOwnership.read(kubeconfig.resolveSibling("ownership.json"), coordination)
          .requireIncompleteCreation(runId);
    }
    requireApiIdentityIfReachable();
    final var expectedVolumes = new java.util.HashSet<>(cluster.volumes());
    expectedVolumes.addAll(volumes(current));
    for (var node : current) {
      commands.run(context.command("rm", "--force", "--volumes", node.path("Id").asText()),
          "", Duration.ofSeconds(45)).checked();
    }
    if (!inspect(commands, context, cluster.name()).isEmpty()) {
      throw new IllegalStateException("owned kind nodes survived cleanup");
    }
    requireVolumesGone(List.copyOf(expectedVolumes));
  }

  private void requireOwnedNodes(List<JsonNode> current, Path marker) {
    final Set<String> expected = Set.copyOf(cluster.nodeIds());
    for (var node : current) {
      if (!hasOwnerMount(node, marker, context)
          || (!expected.isEmpty() && !expected.contains(node.path("Id").asText()))) {
        throw new IllegalStateException("kind node ownership changed; refusing cluster deletion");
      }
    }
  }

  private void requireApiIdentityIfReachable() throws Exception {
    if (cluster.uid().isEmpty() || !Files.isRegularFile(kubeconfig)) {
      return;
    }
    final var result = commands.run(List.of("kubectl", "--kubeconfig", kubeconfig.toString(),
        "--context", "kind-" + cluster.name(), "--request-timeout=10s", "get", "namespace",
        "kube-system", "-o", "json"), "", Duration.ofSeconds(15));
    if (result.exitCode() == 0
        && !cluster.uid().equals(new ObjectMapper().readTree(result.checked())
        .path("metadata").path("uid").asText())) {
      throw new IllegalStateException("reachable cluster UID changed; refusing node deletion");
    }
  }

  private void requireVolumesGone(List<String> names) throws Exception {
    for (String name : names) {
      final var result = commands.run(context.command("volume", "inspect", name), "",
          Duration.ofSeconds(15));
      if (result.exitCode() == 0) {
        throw new IllegalStateException("recorded kind volume survived node cleanup: " + name);
      }
      if (!result.error().toLowerCase(java.util.Locale.ROOT).contains("no such volume")) {
        result.checked();
      }
    }
  }

  /** Docker Desktop's observed prefix is accepted only for that explicitly selected context. */
  static boolean hasOwnerMount(JsonNode node, Path marker, DockerContext context) {
    final String expected = marker.toString();
    return java.util.stream.StreamSupport.stream(node.path("Mounts").spliterator(), false)
        .anyMatch(mount -> "bind".equals(mount.path("Type").asText())
            && "/etc/simplematch-verification-owner".equals(mount.path("Destination").asText())
            && !mount.path("RW").asBoolean()
            && (expected.equals(mount.path("Source").asText())
                || ("desktop-linux".equals(context.name())
                    && ("/host_mnt" + expected).equals(mount.path("Source").asText()))));
  }
}

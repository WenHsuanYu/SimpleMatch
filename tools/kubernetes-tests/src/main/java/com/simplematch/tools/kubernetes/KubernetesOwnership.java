package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Persists actual creation state separately from later readiness and API observations. */
record KubernetesOwnership(String runId, DockerIdentity docker, Cluster cluster,
    String namespace, String namespaceUid, KindMode mode, Path kubeconfig) {
  record Cluster(String name, String uid, boolean created, boolean prepared,
      List<String> nodeIds, List<String> volumes) {
    Cluster {
      nodeIds = List.copyOf(nodeIds);
      volumes = List.copyOf(volumes);
      if (new java.util.HashSet<>(nodeIds).size() != nodeIds.size()) {
        throw new IllegalArgumentException("cluster node identities must be unique");
      }
      if (prepared && (uid.isBlank() || nodeIds.size() != 4)) {
        throw new IllegalArgumentException("prepared cluster requires its UID and four node IDs");
      }
    }
  }

  static KubernetesOwnership read(Path receipt, Path coordination) throws Exception {
    if (Files.isSymbolicLink(receipt)
        || !Files.isRegularFile(receipt, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("original ownership receipt is unavailable");
    }
    return KubernetesOwnershipReader.read(receipt, coordination);
  }

  /** Rejects a creating run that never released its dedicated cluster for reuse. */
  static void requireReusable(KubernetesSettings settings, List<JsonNode> nodes, String uid)
      throws Exception {
    final var sources = nodes.stream().flatMap(node -> java.util.stream.StreamSupport.stream(
        node.path("Mounts").spliterator(), false))
        .filter(mount -> "/etc/simplematch-verification-owner".equals(
            mount.path("Destination").asText()))
        .map(mount -> mount.path("Source").asText()).toList();
    if (sources.isEmpty()) {
      return; // Canonical clusters created by the existing manager have no S3 creation authority.
    }
    final String source = sources.getFirst();
    final Path marker = Path.of("desktop-linux".equals(settings.dockerContext().name())
        && source.startsWith("/host_mnt/") ? source.substring("/host_mnt".length()) : source)
        .toAbsolutePath().normalize();
    final var owner = read(marker.resolveSibling("ownership.json"), settings.coordination());
    owner.requireReusableCluster(uid,
        nodes.stream().map(node -> node.path("Id").asText()).toList());
    if (!settings.dockerContext().equals(owner.docker().context())
        || !owner.kubeconfig().resolveSibling("owner").equals(marker)
        || !nodes.stream().allMatch(node -> OwnedKindCluster.hasOwnerMount(
            node, marker, settings.dockerContext()))
        || !owner.runId().equals(Files.readString(marker))) {
      throw new IllegalStateException("dedicated cluster creation ownership changed");
    }
    owner.docker().requireCurrent(settings.dockerContext().commands(settings.root()));
  }

  void requireReusableCluster(String uid, List<String> nodes) {
    if (mode != KindMode.DEDICATED || !cluster.created() || !cluster.prepared()
        || !uid.equals(cluster.uid())
        || !java.util.Set.copyOf(nodes).equals(java.util.Set.copyOf(cluster.nodeIds()))) {
      throw new IllegalStateException(
          "dedicated creation is incomplete; use its ownership.json cleanup");
    }
  }

  /** A stale partial receipt cannot revoke a completed dedicated cluster's preservation policy. */
  void requireIncompleteCreation(String expectedRun) {
    if (!expectedRun.equals(runId) || mode != KindMode.DEDICATED
        || !cluster.created() || cluster.prepared()) {
      throw new IllegalStateException(
          "dedicated creation is already prepared; refusing node deletion");
    }
  }

  void write(Path evidence) throws Exception {
    final var values = new LinkedHashMap<String, Object>();
    values.put("schema_version", 3);
    values.put("run_id", runId);
    values.put("docker_context", docker.context().name());
    values.put("daemon_id", docker.daemonId());
    values.put("cluster", cluster.name());
    values.put("cluster_uid", cluster.uid());
    values.put("cluster_created", cluster.created());
    values.put("cluster_prepared", cluster.prepared());
    values.put("node_ids", cluster.nodeIds());
    values.put("node_volumes", cluster.volumes());
    values.put("namespace", namespace);
    values.put("namespace_uid", namespaceUid);
    values.put("mode", mode.value());
    values.put("kubeconfig", kubeconfig.toString());
    // Update the private current state first; historical evidence cannot retain stale authority.
    final Path privateDirectory = Objects.requireNonNull(
        kubeconfig.getParent(), "ownership requires a private kubeconfig directory");
    writeAtomic(privateDirectory, values);
    if (!evidence.equals(privateDirectory)) {
      writeAtomic(evidence, values);
    }
  }

  private static void writeAtomic(Path directory, LinkedHashMap<String, Object> values)
      throws Exception {
    final Path temporary = Files.createTempFile(directory, "ownership-", ".tmp");
    try {
      Files.write(temporary, new ObjectMapper().writeValueAsBytes(values));
      Files.move(temporary, directory.resolve("ownership.json"), StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}

package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesOwnershipTest {
  private static final String RUN = "12345678-1234-1234-1234-123456789012";
  @TempDir Path temporary;

  @Test
  void readsTheOriginalOwnedNamespaceAndPrivateConfiguration() throws Exception {
    final var owner = KubernetesOwnership.read(writeReceipt(receipt()), temporary);
    assertEquals(RUN, owner.runId());
    assertEquals("desktop-linux", owner.docker().context().name());
    assertEquals("simplematch-s3-12345678-123", owner.namespace());
    assertEquals(nodes(), owner.cluster().nodeIds());
  }

  @Test
  void rejectsAnUnrelatedNamespaceOrConfigurationBeforeAnyExternalOperation() throws Exception {
    final var unrelated = new LinkedHashMap<>(receipt());
    unrelated.put("namespace", "desktop");
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesOwnership.read(writeReceipt(unrelated), temporary));
    final var escaped = new LinkedHashMap<>(receipt());
    escaped.put("kubeconfig", temporary.resolve("foreign-kubeconfig").toString());
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesOwnership.read(writeReceipt(escaped), temporary));
  }

  @Test
  void creationAuthorityIsNotInferredFromAMissingApiObservation() throws Exception {
    final var existing = new LinkedHashMap<>(receipt());
    existing.put("cluster_created", false);
    existing.put("cluster_prepared", false);
    existing.put("cluster_uid", "");
    final var owner = KubernetesOwnership.read(writeReceipt(existing), temporary);
    org.junit.jupiter.api.Assertions.assertFalse(owner.mode().mayDelete(
        owner.cluster().created(), owner.cluster().prepared()));
    existing.put("cluster_created", true);
    final var partial = KubernetesOwnership.read(writeReceipt(existing), temporary);
    org.junit.jupiter.api.Assertions.assertTrue(partial.mode().mayDelete(
        partial.cluster().created(), partial.cluster().prepared()));
  }

  @Test
  void preservesTheLatestCreationStateBesideThePrivateClient() throws Exception {
    final var original = new LinkedHashMap<>(receipt());
    original.put("cluster_prepared", false);
    final var owner = KubernetesOwnership.read(writeReceipt(original), temporary);
    Files.createDirectories(owner.kubeconfig().getParent());
    final Path evidence = Files.createDirectory(temporary.resolve("evidence"));
    owner.write(evidence);
    assertThrows(IllegalStateException.class,
        () -> owner.requireReusableCluster("cluster-uid", nodes()));
    owner.requireIncompleteCreation(RUN);
    final Path current = owner.kubeconfig().resolveSibling("ownership.json");
    org.junit.jupiter.api.Assertions.assertTrue(Files.isRegularFile(current));
    org.junit.jupiter.api.Assertions.assertFalse(
        KubernetesOwnership.read(current, temporary).cluster().prepared());
    final var prepared = new KubernetesOwnership(owner.runId(), owner.docker(),
        new KubernetesOwnership.Cluster(owner.cluster().name(), owner.cluster().uid(), true, true,
            owner.cluster().nodeIds(), owner.cluster().volumes()), owner.namespace(), "",
        owner.mode(), owner.kubeconfig());
    prepared.write(evidence);
    KubernetesOwnership.read(current, temporary).requireReusableCluster("cluster-uid", nodes());
    assertThrows(IllegalStateException.class,
        () -> KubernetesOwnership.read(current, temporary).requireIncompleteCreation(RUN));
    org.junit.jupiter.api.Assertions.assertTrue(
        KubernetesOwnership.read(current, temporary).cluster().prepared());
    assertEquals(Files.readString(current), Files.readString(evidence.resolve("ownership.json")));
  }

  private Map<String, Object> receipt() {
    final var receipt = new LinkedHashMap<String, Object>();
    receipt.put("docker_context", "desktop-linux");
    receipt.put("cluster_created", true);
    receipt.put("cluster_prepared", true);
    receipt.put("node_volumes", List.of());
    receipt.putAll(Map.of("schema_version", 3, "run_id", RUN, "daemon_id", "daemon",
        "cluster", "simplematch-live", "cluster_uid", "cluster-uid",
        "namespace", "simplematch-s3-12345678-123", "namespace_uid", "original-uid",
        "mode", "dedicated", "kubeconfig", temporary.resolve("run-" + RUN)
            .resolve("kubeconfig").toString(), "node_ids", nodes()));
    return receipt;
  }

  private List<String> nodes() {
    return List.of("a", "b", "c", "d").stream().map(value -> value.repeat(64)).toList();
  }

  private Path writeReceipt(Map<String, Object> document) throws Exception {
    final Path path = temporary.resolve("ownership.json");
    Files.write(path, new ObjectMapper().writeValueAsBytes(document));
    return path;
  }
}

package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnedNamespaceTest {
  @TempDir Path temporary;

  @Test
  void deletionUsesUidAndVersionAndRefusesAReplacedNamespace() throws Exception {
    try (var api = new KubernetesApiFixture(temporary)) {
      api.put("/api/v1/namespaces/scenario", namespace("scenario", "original", "run"));
      final var client = api.client();
      final var target = new OwnedNamespace(client, "scenario", "original", "run");
      target.requestDeletion();
      final var preconditions = api.deletions.get(0).path("preconditions");
      assertEquals("original", preconditions.path("uid").asText());
      assertEquals("42", preconditions.path("resourceVersion").asText());
      target.requestDeletion();
      assertEquals(1, api.deletions.size());
      api.put("/api/v1/namespaces/scenario", namespace("scenario", "replacement", "run"));
      assertThrows(IllegalStateException.class, target::requestDeletion);
      assertEquals(1, api.deletions.size());
    }
  }

  @Test
  void absentResourcesHaveOneOptionalRepresentationAndCannotSatisfyRequiredReads() throws Exception {
    try (var api = new KubernetesApiFixture(temporary)) {
      final var client = api.client();
      for (String kind : List.of("namespace", "storageclass")) {
        assertTrue(client.find("get", kind, "missing", "--ignore-not-found", "-o", "json").isEmpty());
        assertThrows(IOException.class,
            () -> client.read("get", kind, "missing", "--ignore-not-found", "-o", "json"));
      }
      api.put("/api/v1/namespaces/kube-system", namespace("kube-system", "cluster", "system"));
      final var nodes = List.of("a", "b", "c", "d").stream().map(s -> s.repeat(64)).toList();
      final var owner = new KubernetesOwnership("run",
          new DockerIdentity(new DockerContext("desktop-linux"), "daemon"),
          new KubernetesOwnership.Cluster("fixture", "cluster", false, true, nodes, List.of()),
          "scenario", "original", KindMode.DEDICATED, temporary.resolve("kubeconfig.json"));
      KubernetesNamespace.retained(client, owner).cleanup(temporary);
      assertTrue(api.deletions.isEmpty());
    }
  }

  private Map<String, Object> namespace(String name, String uid, String owner) {
    return Map.of("apiVersion", "v1", "kind", "Namespace", "metadata",
        Map.of("name", name, "uid", uid, "resourceVersion", "42", "labels",
            Map.of(OwnedNamespace.OWNER_LABEL, owner)));
  }
}

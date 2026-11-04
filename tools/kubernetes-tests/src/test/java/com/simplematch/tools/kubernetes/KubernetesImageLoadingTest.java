package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesImageLoadingTest {
  @TempDir Path temporary;

  @Test
  void selectsOnlyTheThreeMatchingWorkloadNodes() throws Exception {
    try (var api = new KubernetesApiFixture(temporary)) {
      api.put("/api/v1/nodes", Map.of("apiVersion", "v1", "kind", "NodeList", "items",
          List.of(node("control", "control-plane"), node("worker-2", "local-resilience"),
              node("worker-1", "local-resilience"), node("worker-3", "local-resilience"))));
      assertEquals(List.of("worker-1", "worker-2", "worker-3"),
          KubernetesImages.workerNodes(api.client()));
    }
  }

  @Test
  void storesLargeArchivesInTheRunEvidenceDirectoryInsteadOfCoordinationTmpfs()
      throws Exception {
    final Path root = Path.of(System.getProperty("verificationRepoRoot"));
    final Path evidence = Files.createTempDirectory(Files.createDirectories(root.resolve("out")),
        "image-archive-test-");
    try {
      final var docker = new DockerContext("desktop-linux");
      final var settings = new KubernetesSettings(root, evidence, temporary,
          LocalDate.of(2026, 8, 17), evidence, "test", KindMode.DISPOSABLE,
          false, docker, "risk-command");
      final var kind = new KindEnvironment(settings,
          new VerificationContext("test-run-12345", evidence), new DockerIdentity(docker, "test"));
      final Path archive = kind.temporaryImageArchive();
      try {
        assertEquals(evidence, archive.getParent());
        assertTrue(Files.isRegularFile(archive));
      } finally {
        Files.deleteIfExists(archive);
      }
    } finally {
      Files.delete(evidence);
    }
  }

  private static Map<String, Object> node(String name, String pool) {
    return Map.of("metadata", Map.of("name", name,
        "labels", Map.of("simplematch.io/node-pool", pool)));
  }
}

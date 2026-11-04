package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesDiagnosticsTest {
  @TempDir Path temporary;

  @Test
  void retainsFailedControlPlaneExitAndLogsFromItsActualNamespace() throws Exception {
    try (var api = new KubernetesApiFixture(temporary)) {
      final var pod = Map.of("apiVersion", "v1", "kind", "Pod", "metadata",
          Map.of("name", "kube-scheduler", "namespace", "kube-system"),
          "spec", Map.of("containers", List.of(Map.of("name", "scheduler"))), "status",
          Map.of("containerStatuses", List.of(Map.of("name", "scheduler", "restartCount", 2,
              "state", Map.of("running", Map.of()), "lastState",
              Map.of("terminated", Map.of("reason", "Error", "exitCode", 1))))));
      api.put("/api/v1/namespaces/kube-system/pods", Map.of("apiVersion", "v1", "kind", "PodList",
          "items", List.of(pod)));
      api.put("/api/v1/namespaces/kube-system/pods/kube-scheduler", pod);
      api.put("/api/v1/namespaces/kube-system/pods/kube-scheduler/log",
          "leader election lost password=fixture-secret");
      new KubernetesDiagnostics(api.client(), new VerificationContext("run", temporary),
          Duration.ofSeconds(10)).controlPlane();
      final var snapshot = api.json.readTree(
          temporary.resolve("diagnostics-control-plane.json").toFile());
      assertTrue(snapshot.at("/items/0/status/containerStatuses/0/lastState/terminated")
          .path("reason").asText().equals("Error"));
      final String logs = Files.readString(temporary.resolve("diagnostics-control-kube-scheduler.log"));
      assertTrue(logs.contains("leader election lost"));
      assertFalse(logs.contains("fixture-secret"));
      assertTrue(api.requests.stream().anyMatch(uri ->
          uri.startsWith("/api/v1/namespaces/kube-system/pods/kube-scheduler/log")
              && uri.contains("previous=true")));
    }
  }
}

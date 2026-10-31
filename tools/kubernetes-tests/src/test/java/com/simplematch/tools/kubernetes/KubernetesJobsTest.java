package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesJobsTest {
  @TempDir Path temporary;

  @Test
  void canonicalRetryCanCompleteAndStillPreservesTheFailedAttempt() throws Exception {
    try (var api = new KubernetesApiFixture(temporary)) {
      configure(api, "Complete");
      KubernetesJobs.awaitComplete(api.client(), "migration", new VerificationContext("run", temporary));
      assertTrue(Files.readString(temporary.resolve("migration-pods.json")).contains("Failed"));
      assertTrue(Files.readString(temporary.resolve("migration-failed.log")).contains("retry cause"));
    }
  }

  @Test
  void terminalFailureRetainsTheJobPodsAndRedactedCauseBeforeThrowing() throws Exception {
    try (var api = new KubernetesApiFixture(temporary)) {
      configure(api, "Failed");
      assertThrows(IllegalStateException.class, () -> KubernetesJobs.awaitComplete(api.client(),
          "migration", new VerificationContext("run", temporary)));
      assertTrue(Files.isRegularFile(temporary.resolve("migration-job.json")));
      assertTrue(Files.isRegularFile(temporary.resolve("migration-pods.json")));
      final String logs = Files.readString(temporary.resolve("migration-failed.log"));
      assertTrue(logs.contains("retry cause"));
      org.junit.jupiter.api.Assertions.assertFalse(logs.contains("fixture-secret"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void preservesInitOnlyAndPreviousExecutionLogsBeforeAcceptingARetry(boolean initOnly)
      throws Exception {
    try (var api = new KubernetesApiFixture(temporary)) {
      configure(api, "Complete");
      final var failed = new java.util.LinkedHashMap<>(pod("failed", "Failed", 1));
      if (initOnly) {
        failed.put("spec", Map.of("containers", List.of(Map.of("name", "main", "image", "fixture:tag")),
            "initContainers", List.of(Map.of("name", "wait-for-postgres", "image", "fixture:tag"))));
        failed.put("status", Map.of("phase", "Failed", "initContainerStatuses",
            List.of(Map.of("name", "wait-for-postgres", "state",
                Map.of("terminated", Map.of("exitCode", 1))))));
      } else {
        failed.put("status", Map.of("phase", "Failed", "containerStatuses",
            List.of(Map.of("name", "main", "state", Map.of("waiting", Map.of("reason", "CrashLoopBackOff")),
                "lastState", Map.of("terminated", Map.of("exitCode", 1))))));
      }
      api.put("/api/v1/namespaces/scenario/pods/failed", failed);
      api.put("/api/v1/namespaces/scenario/pods", Map.of("apiVersion", "v1", "kind", "PodList",
          "items", List.of(failed, pod("succeeded", "Succeeded", 0))));
      KubernetesJobs.awaitComplete(api.client(), "migration", new VerificationContext("run", temporary));
      assertTrue(Files.readString(temporary.resolve("migration-failed.log")).contains("retry cause"));
      assertTrue(api.requests.stream().anyMatch(uri -> uri.contains("/pods/failed/log")
          && uri.contains(initOnly ? "container=wait-for-postgres" : "previous=true")));
    }
  }

  private void configure(KubernetesApiFixture api, String condition) throws Exception {
    api.put("/apis/batch/v1/namespaces/scenario/jobs/migration", Map.of("apiVersion", "batch/v1",
        "kind", "Job", "metadata", Map.of("name", "migration", "uid", "job-uid", "labels",
            Map.of(OwnedNamespace.OWNER_LABEL, "run")), "status", Map.of("conditions",
            List.of(Map.of("type", condition, "status", "True")))));
    final var failed = pod("failed", "Failed", 1);
    final var succeeded = pod("succeeded", "Succeeded", 0);
    api.put("/api/v1/namespaces/scenario/pods", Map.of("apiVersion", "v1", "kind", "PodList",
        "items", List.of(failed, succeeded)));
    api.put("/api/v1/namespaces/scenario/pods/failed", failed);
    api.put("/api/v1/namespaces/scenario/pods/succeeded", succeeded);
    api.log("failed", "retry cause password=fixture-secret\n");
  }

  private Map<String, Object> pod(String name, String phase, int exit) {
    return Map.of("apiVersion", "v1", "kind", "Pod", "metadata", Map.of("name", name, "namespace", "scenario",
        "uid", name + "-uid", "labels", Map.of(OwnedNamespace.OWNER_LABEL, "run"),
        "ownerReferences", List.of(Map.of("kind", "Job", "uid", "job-uid"))),
        "spec", Map.of("containers", List.of(Map.of("name", "main", "image", "fixture:tag"))),
        "status", Map.of("phase", phase, "containerStatuses", List.of(Map.of("name", "main",
            "state", Map.of("terminated", Map.of("exitCode", exit))))));
  }
}

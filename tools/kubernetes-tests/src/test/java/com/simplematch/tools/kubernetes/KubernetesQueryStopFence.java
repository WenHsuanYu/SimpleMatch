package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Verifies physical Query termination instead of inferring it from a zero-Pod API result. */
final class KubernetesQueryStopFence {
  private static final String WORKLOAD = "query-service";
  private final KubernetesScenarioTarget target;
  private final KubernetesOwnership ownership;
  private final BoundedCommand docker;
  private final ObjectMapper json = new ObjectMapper();

  KubernetesQueryStopFence(KubernetesBusinessEnvironment.PublicOrder context) throws Exception {
    target = context.target();
    ownership = KubernetesOwnership.read(
        target.run().evidence().resolve("ownership.json"), context.settings().coordination());
    assertEquals(target.run().runId(), ownership.runId());
    assertEquals(target.namespace(), ownership.namespace());
    docker = ownership.docker().context().commands(context.settings().root());
    requireNamespace();
  }

  List<Task> capture() throws Exception {
    requireNamespace();
    final var pods = queryPods();
    assertEquals(2, pods.size(), "a real two-Pod Query generation must precede isolation");
    final var tasks = new ArrayList<Task>();
    for (var pod : pods) {
      final String podUid = pod.path("metadata").path("uid").asText();
      final String node = pod.path("spec").path("nodeName").asText();
      final String runtimeId = pod.at("/status/containerStatuses/0/containerID").asText();
      assertFalse(podUid.isBlank() || node.isBlank() || !runtimeId.startsWith("containerd://"),
          "Query Pod is missing its physical CRI identity");
      assertEquals(target.run().runId(),
          pod.path("metadata").path("labels").path(OwnedNamespace.OWNER_LABEL).asText());
      requireNode(node, Duration.ofSeconds(10));
      final String containerId = runtimeId.substring("containerd://".length());
      final var inspection = json.readTree(docker.run(ownership.docker().context().command(
          "exec", node, "crictl", "inspect", containerId), "",
          Duration.ofSeconds(10)).checked());
      assertEquals("CONTAINER_RUNNING", inspection.at("/status/state").asText());
      assertEquals(podUid, inspection.at("/status/labels/io.kubernetes.pod.uid").asText());
      assertEquals(target.namespace(),
          inspection.at("/status/labels/io.kubernetes.pod.namespace").asText());
      assertEquals(WORKLOAD, inspection.at("/status/metadata/name").asText());
      final long pid = inspection.at("/info/pid").asLong(-1);
      assertTrue(pid > 0, "Query CRI process is not running");
      final String start = KubernetesQueryPolicyReplacement.startTime(
          docker.run(ownership.docker().context().command(
              "exec", node, "cat", "/proc/" + pid + "/stat"), "",
              Duration.ofSeconds(10)).checked());
      tasks.add(new Task(node, podUid, containerId, pid, start));
    }
    return List.copyOf(tasks);
  }

  void awaitStopped(List<Task> old, Duration duration) throws Exception {
    assertEquals(2, old.size(), "Query isolation requires two previously live tasks");
    final var budget = OperationBudget.start(duration);
    while (true) {
      budget.remaining();
      requireNamespace();
      final var deployment = target.client().read("get", "deployment", WORKLOAD, "-o", "json");
      assertEquals(target.run().runId(),
          deployment.path("metadata").path("labels").path(OwnedNamespace.OWNER_LABEL).asText());
      if (deployment.path("spec").path("replicas").asInt(-1) == 0
          && deployment.path("status").path("replicas").asInt() == 0
          && deployment.path("status").path("readyReplicas").asInt() == 0
          && queryPods().isEmpty() && allStopped(old, budget)) {
        return;
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(250)));
    }
  }

  private boolean allStopped(List<Task> old, OperationBudget budget) throws Exception {
    for (var node : KubernetesImages.workerNodes(target.client())) {
      requireNode(node, budget.remaining(Duration.ofSeconds(5)));
      final var result = docker.run(ownership.docker().context().command(
          "exec", node, "crictl", "ps", "-o", "json"), "",
          budget.remaining(Duration.ofSeconds(5))).checked();
      final var running = json.readTree(result).path("containers");
      assertTrue(running.isArray(), "CRI did not provide a running-task list");
      for (var task : running) {
        final var name = task.path("metadata").path("name").asText();
        final var namespace = task.path("labels")
            .path("io.kubernetes.pod.namespace").asText();
        if (old.stream().anyMatch(previous ->
            previous.containerId().equals(task.path("id").asText()))
            || (WORKLOAD.equals(name)
                && (namespace.isBlank() || target.namespace().equals(namespace)))) {
          return false;
        }
      }
    }
    for (var task : old) {
      final String remaining = docker.run(ownership.docker().context().command(
          "exec", task.node(), "ctr", "-n", "k8s.io", "tasks", "ls", "-q"), "",
          budget.remaining(Duration.ofSeconds(5))).checked();
      if (remaining.lines().anyMatch(task.containerId()::equals)) {
        return false;
      }
      final var inspection = docker.run(ownership.docker().context().command(
          "exec", task.node(), "crictl", "inspect", task.containerId()), "",
          budget.remaining(Duration.ofSeconds(5)));
      if (inspection.exitCode() == 0) {
        if ("CONTAINER_RUNNING".equals(json.readTree(inspection.checked())
            .at("/status/state").asText())) {
          return false;
        }
      } else if (!inspection.error().toLowerCase(Locale.ROOT).contains("not found")
          && !inspection.error().toLowerCase(Locale.ROOT).contains("notfound")) {
        inspection.checked();
        throw new IllegalStateException("Query CRI task absence was not established");
      }
      final var process = docker.run(ownership.docker().context().command(
          "exec", task.node(), "cat", "/proc/" + task.pid() + "/stat"), "",
          budget.remaining(Duration.ofSeconds(5)));
      if (process.exitCode() == 0) {
        if (task.startTime().equals(
            KubernetesQueryPolicyReplacement.startTime(process.checked()))) {
          return false;
        }
      } else if (!process.error().contains("No such file or directory")) {
        process.checked();
        throw new IllegalStateException("Query PID absence was not established");
      }
    }
    return true;
  }

  private JsonNode queryPods() throws Exception {
    return target.client().read("get", "pods", "-l",
        "app.kubernetes.io/name=query-service,"
            + OwnedNamespace.OWNER_LABEL + "=" + target.run().runId(),
        "-o", "json").required("items");
  }

  private void requireNamespace() throws Exception {
    final var namespace = target.client().read("get", "namespace", target.namespace(), "-o", "json");
    assertEquals(ownership.namespaceUid(), namespace.path("metadata").path("uid").asText());
    assertEquals(target.run().runId(),
        namespace.path("metadata").path("labels").path(OwnedNamespace.OWNER_LABEL).asText());
  }

  private void requireNode(String node, Duration timeout) throws Exception {
    final var value = json.readTree(docker.run(ownership.docker().context().command(
        "inspect", node), "", timeout).checked());
    assertTrue(value.isArray() && value.size() == 1);
    assertTrue(ownership.cluster().nodeIds().contains(value.get(0).path("Id").asText()),
        "Query worker differs from the run's original cluster identity");
  }

  record Task(String node, String podUid, String containerId, long pid, String startTime) {}
}

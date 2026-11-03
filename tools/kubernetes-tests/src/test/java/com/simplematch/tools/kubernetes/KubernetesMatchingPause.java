package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/** Signals only a verified run-owned Matching task; refuses restoration after identity drift. */
final class KubernetesMatchingPause implements AutoCloseable {
  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final KubernetesOwnership owner;
  private final BoundedCommand commands;
  private final String pod;
  private final String podUid;
  private final String node;
  private final String nodeId;
  private final String container;
  private final long pid;
  private boolean stopped;
  private OperationBudget pausedBudget;
  private String pausedState = "";
  private final ObjectMapper json = new ObjectMapper();

  private KubernetesMatchingPause(KubernetesBusinessEnvironment.PublicOrder context, int partition)
      throws Exception {
    this.context = context;
    owner = KubernetesOwnership.read(context.target().run().evidence().resolve("ownership.json"),
        context.settings().coordination());
    if (!owner.runId().equals(context.target().run().runId())
        || !owner.namespace().equals(context.target().namespace())) {
      throw new IllegalStateException("Matching fault receipt differs from this run");
    }
    commands = owner.docker().context().commands(context.settings().root());
    pod = "matching-" + partition;
    final var observed = context.target().client().read("get", "pod", pod, "-o", "json");
    podUid = observed.path("metadata").path("uid").asText();
    if (!owner.runId().equals(observed.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText()) || podUid.isBlank()) {
      throw new IllegalStateException("Matching fault Pod is not run-owned");
    }
    node = observed.path("spec").path("nodeName").asText();
    nodeId = json.readTree(commands.run(owner.docker().context().command("inspect", node), "",
        Duration.ofSeconds(15)).checked()).get(0).path("Id").asText();
    if (!owner.cluster().nodeIds().contains(nodeId)) {
      throw new IllegalStateException("Matching fault node differs from original ownership");
    }
    final String runtime = java.util.stream.StreamSupport.stream(
        observed.path("status").path("containerStatuses").spliterator(), false)
        .filter(value -> "matching".equals(value.path("name").asText()))
        .map(value -> value.path("containerID").asText()).findFirst().orElseThrow();
    if (!runtime.matches("containerd://[0-9a-f]{64}")) {
      throw new IllegalStateException("Matching task has no exact containerd identity");
    }
    container = runtime.substring("containerd://".length());
    pid = inspect().path("info").path("pid").asLong();
    if (pid <= 0 || "T".equals(processState())) {
      throw new IllegalStateException("Matching process is absent or already stopped");
    }
  }

  static KubernetesMatchingPause stop(KubernetesBusinessEnvironment.PublicOrder context, int partition)
      throws Exception {
    final var pause = new KubernetesMatchingPause(context, partition);
    try {
      pause.stopped = true;
      // Leave room for restoration before the native 30s stale-file/probe failure window.
      pause.pausedBudget = OperationBudget.start(Duration.ofSeconds(20));
      pause.signal("SIGSTOP", pause.pausedBudget);
      pause.pausedState = pause.awaitStopped(true, pause.pausedBudget);
      pause.save();
      return pause;
    } catch (Exception failure) {
      try {
        pause.close();
      } catch (Exception restoration) {
        failure.addSuppressed(restoration);
      }
      throw failure;
    }
  }

  private JsonNode inspect() throws Exception {
    return inspect(Duration.ofSeconds(15));
  }

  private JsonNode inspect(Duration budget) throws Exception {
    final var status = json.readTree(commands.run(owner.docker().context().command("exec", node,
        "crictl", "inspect", container), "", budget).checked());
    if (!container.equals(status.path("status").path("id").asText())
        || !podUid.equals(status.path("status").path("labels").path("io.kubernetes.pod.uid").asText())) {
      throw new IllegalStateException("Matching task no longer belongs to the selected Pod");
    }
    return status;
  }

  private void signal(String signal, OperationBudget budget) throws Exception {
    owner.docker().requireCurrent(commands, budget.remaining());
    final String currentNode = json.readTree(commands.run(owner.docker().context()
        .command("inspect", node), "", budget.remaining()).checked()).get(0).path("Id").asText();
    if (!nodeId.equals(currentNode)
        || inspect(budget.remaining()).path("info").path("pid").asLong() != pid) {
      throw new IllegalStateException("Matching task or node changed; refusing signal");
    }
    commands.run(owner.docker().context().command("exec", node, "ctr", "--namespace", "k8s.io",
        "tasks", "kill", "--signal", signal, container), "", budget.remaining()).checked();
  }

  private String processState() throws Exception {
    return processState(Duration.ofSeconds(10));
  }

  private String processState(Duration budget) throws Exception {
    final String status = commands.run(owner.docker().context().command("exec", node, "cat",
        "/proc/" + pid + "/status"), "", budget).checked();
    final var state = Pattern.compile("(?m)^State:\\s+(\\S)").matcher(status);
    if (!state.find()) {
      throw new IllegalStateException("Matching process state is unavailable");
    }
    return state.group(1);
  }

  private void save() throws Exception {
    Files.write(context.target().run().evidence().resolve("matching-pause.json"),
        json.writeValueAsBytes(Map.of("pod_uid", podUid, "node_id", nodeId,
            "container_id", container, "pid", pid, "paused_state", pausedState,
            "restored", !stopped)));
  }

  private String awaitStopped(boolean expected, OperationBudget budget) throws Exception {
    final var observation = OperationBudget.start(budget.remaining(Duration.ofSeconds(5)));
    while (true) {
      final String state = processState(observation.remaining());
      if ("T".equals(state) == expected) {
        return state;
      }
      Thread.sleep(observation.remaining(Duration.ofMillis(50)));
    }
  }

  /** Caps fault work while stopped; resumed work retains the caller's scenario budget. */
  Duration limit(Duration available) throws java.util.concurrent.TimeoutException {
    return stopped ? pausedBudget.remaining(available) : available;
  }

  /** Resumes only the original task, including when the scenario was interrupted. */
  @Override
  public void close() throws Exception {
    final boolean interrupted = Thread.interrupted();
    try {
      if (stopped) {
        final var restoration = OperationBudget.start(Duration.ofSeconds(10));
        signal("SIGCONT", restoration);
        awaitStopped(false, restoration);
        stopped = false;
        save();
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}

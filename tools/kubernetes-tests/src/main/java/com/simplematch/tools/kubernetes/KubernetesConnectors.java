package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Registers selected CDC connectors through the run-owned Connect Pod and observes convergence. */
final class KubernetesConnectors {
  private final KubernetesScenarioTarget target;

  KubernetesConnectors(KubernetesScenarioTarget target) {
    this.target = target;
  }

  /** Applies one idempotent pause/resume request, then observes the connector and every task. */
  void setState(String name, String state) throws Exception {
    setState(name, state, Duration.ofSeconds(60));
  }

  void setState(String name, String state, Duration duration) throws Exception {
    if (!java.util.Set.of("PAUSED", "RUNNING").contains(state)) {
      throw new IllegalArgumentException("expected PAUSED or RUNNING connector state");
    }
    final var budget = OperationBudget.start(duration);
    final String pod = connectPod(budget.remaining());
    connectRest(pod, "PUT", "/connectors/" + name
        + ("PAUSED".equals(state) ? "/pause" : "/resume"), "", budget.remaining());
    final var json = new ObjectMapper();
    while (true) {
      final var status = json.readTree(
          connectRest(pod, "GET", "/connectors/" + name + "/status", "",
              budget.remaining()).checked());
      java.nio.file.Files.writeString(target.run().evidence().resolve(name + "-state.json"),
          DiagnosticText.redact(status.toPrettyString()));
      if (state.equals(status.path("connector").path("state").asText())
          && !status.path("tasks").isEmpty()
          && java.util.stream.StreamSupport.stream(status.path("tasks").spliterator(), false)
              .allMatch(task -> state.equals(task.path("state").asText()))) {
        budget.remaining();
        return;
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(250)));
    }
  }

  /** Registers through an owned Pod's local REST endpoint and reconciles uncertain writes. */
  void register(String name) throws Exception {
    final var json = new ObjectMapper();
    final String config = target.client().read("get", "configmap",
        name + "-connector", "-o", "json")
        .path("data").path("connector.json").asText();
    final var expected = json.readTree(config);
    if (!name.equals(expected.path("name").asText())) {
      throw new IllegalStateException("connector name differs from the selected contract");
    }
    final var pod = connectPod();
    final boolean existing = java.util.stream.StreamSupport.stream(json.readTree(
        connectRest(pod, "GET", "/connectors", "").checked()).spliterator(), false)
        .anyMatch(value -> name.equals(value.asText()));
    // An uncertain POST result is resolved by observing the actual configuration, not repeating it.
    final int registrationExit = existing ? 0
        : connectRest(pod, "POST", "/connectors", config).exitCode();
    java.nio.file.Files.write(target.run().evidence().resolve(name + "-registration.json"),
        json.writeValueAsBytes(Map.of("transport", "owned Pod local REST", "pod", pod,
            "already_present", existing, "registration_exit", registrationExit)));
    final long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
    boolean running = false;
    do {
      requireConnectorConfig(pod, name, expected.path("config"));
      final var status = json.readTree(
          connectRest(pod, "GET", "/connectors/" + name + "/status", "").checked());
      java.nio.file.Files.writeString(target.run().evidence().resolve(name + ".json"),
          DiagnosticText.redact(status.toPrettyString()));
      running = "RUNNING".equals(status.path("connector").path("state").asText())
          && !status.path("tasks").isEmpty()
          && java.util.stream.StreamSupport.stream(status.path("tasks").spliterator(), false)
              .allMatch(task -> "RUNNING".equals(task.path("state").asText()));
      if (!running) {
        Thread.sleep(Duration.ofSeconds(1));
      }
    } while (!running && System.nanoTime() < deadline);
    if (!running) {
      throw new TimeoutException("connector/tasks did not become RUNNING: " + name);
    }
  }

  private String connectPod() throws Exception {
    return connectPod(Duration.ofSeconds(45));
  }

  private String connectPod(Duration budget) throws Exception {
    for (var pod : target.client().readWithin(budget, "get", "pods", "-l",
        "app.kubernetes.io/name=kafka-connect," + OwnedNamespace.OWNER_LABEL + "="
            + target.run().runId(), "-o", "json").path("items")) {
      for (var condition : pod.path("status").path("conditions")) {
        if ("Ready".equals(condition.path("type").asText())
            && "True".equals(condition.path("status").asText())
            && !pod.path("metadata").path("uid").asText().isBlank()) {
          return pod.path("metadata").path("name").asText();
        }
      }
    }
    throw new IllegalStateException("no owned Ready Connect Pod is available");
  }

  private BoundedCommand.Result connectRest(String pod, String method, String path, String body)
      throws Exception {
    return connectRest(pod, method, path, body, Duration.ofSeconds(65));
  }

  private BoundedCommand.Result connectRest(String pod, String method, String path, String body,
      Duration budget) throws Exception {
    final var args = new java.util.ArrayList<>(java.util.List.of("exec", "-i", pod, "--", "curl",
        "--fail-with-body", "--silent", "--show-error", "--connect-timeout", "5",
        "--max-time", "45", "--request", method, "--header", "Content-Type: application/json"));
    if ("POST".equals(method)) {
      args.addAll(java.util.List.of("--data-binary", "@-"));
    } else if ("GET".equals(method)) {
      args.addAll(java.util.List.of(
          "--retry", "2", "--retry-delay", "1", "--retry-max-time", "10"));
    }
    args.add("http://localhost:8083" + path);
    return target.client().execute(body, budget, args.toArray(String[]::new));
  }

  private void requireConnectorConfig(String pod, String name, JsonNode expected) throws Exception {
    final var actual = new ObjectMapper().readTree(
        connectRest(pod, "GET", "/connectors/" + name + "/config", "").checked());
    for (var entry : expected.properties()) {
      if (!actual.has(entry.getKey())
          || !entry.getValue().asText().equals(actual.path(entry.getKey()).asText())) {
        throw new IllegalStateException(
            "existing connector configuration differs: " + entry.getKey());
      }
    }
  }

}

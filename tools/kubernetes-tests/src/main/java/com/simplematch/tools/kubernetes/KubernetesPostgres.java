package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/** Executes fixture SQL through the single run-owned PostgreSQL Pod without exposing its Secret. */
final class KubernetesPostgres {
  private final KubernetesClient client;
  private final String pod;

  KubernetesPostgres(KubernetesScenarioTarget target) throws Exception {
    client = target.client();
    final var pods = client.read("get", "pods", "-l",
        "app.kubernetes.io/name=postgres," + OwnedNamespace.OWNER_LABEL + "="
            + target.run().runId(), "-o", "json").path("items");
    if (pods.size() != 1 || pods.get(0).path("metadata").path("uid").asText().isBlank()) {
      throw new IllegalStateException("run-owned PostgreSQL Pod identity is ambiguous or absent");
    }
    pod = pods.get(0).path("metadata").path("name").asText();
  }

  String execute(String statement, Map<String, String> variables) throws Exception {
    return execute(statement, variables, Duration.ofSeconds(45));
  }

  String execute(String statement, Map<String, String> variables, Duration budget)
      throws Exception {
    final var arguments = new ArrayList<>(List.of("exec", "-i", pod, "--", "psql",
        "--no-psqlrc", "-U", "simplematch", "-d", "simplematch", "-Atq", "-v", "ON_ERROR_STOP=1"));
    variables.forEach((name, value) -> arguments.addAll(List.of("-v", name + "=" + value)));
    return client.execute(statement, budget, arguments.toArray(String[]::new)).checked();
  }

  /**
   * Polls successful SQL observations until convergence; command failures propagate immediately.
   */
  JsonNode await(String statement, Map<String, String> variables, Predicate<JsonNode> ready,
      Duration budget) throws Exception {
    final long deadline = System.nanoTime() + budget.toNanos();
    final var json = new ObjectMapper();
    while (System.nanoTime() < deadline) {
      final String output = execute(statement, variables);
      if (System.nanoTime() >= deadline) {
        break;
      }
      if (!output.isBlank()) {
        final var value = json.readTree(output);
        if (ready.test(value)) {
          return value;
        }
      }
      Thread.sleep(250);
    }
    throw new TimeoutException("PostgreSQL observation did not converge within its budget");
  }
}

package com.simplematch.tools.kubernetes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    final var arguments = new ArrayList<>(List.of("exec", "-i", pod, "--", "psql",
        "--no-psqlrc", "-U", "simplematch", "-d", "simplematch", "-Atq", "-v", "ON_ERROR_STOP=1"));
    variables.forEach((name, value) -> arguments.addAll(List.of("-v", name + "=" + value)));
    return client.run(statement, arguments.toArray(String[]::new));
  }
}

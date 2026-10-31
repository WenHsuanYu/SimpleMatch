package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Uses an explicit private kubeconfig, context and namespace for every invocation. */
final class KubernetesClient {
  private final BoundedCommand commands;
  private final List<String> prefix;
  private final Path config;
  private final String context;
  private final ObjectMapper json = new ObjectMapper();

  KubernetesClient(Path root, Path config, String context, String namespace) {
    commands = new BoundedCommand(root, Map.of());
    this.config = config;
    this.context = context;
    prefix = List.of("kubectl", "--kubeconfig", config.toString(), "--context", context,
        "--namespace", namespace, "--request-timeout=20s");
  }

  BoundedCommand.Result execute(String input, Duration timeout, String... arguments)
      throws Exception {
    final var command = new ArrayList<>(prefix);
    command.addAll(List.of(arguments));
    return commands.run(command, input, timeout);
  }

  String run(String input, String... arguments) throws Exception {
    return execute(input, Duration.ofSeconds(45), arguments).checked();
  }

  JsonNode read(String... arguments) throws Exception {
    return find(arguments).orElseThrow(
        () -> new java.io.IOException("required Kubernetes observation returned no object"));
  }

  JsonNode readWithin(Duration timeout, String... arguments) throws Exception {
    final String output = execute("", timeout, arguments).checked();
    if (output.isBlank()) {
      throw new java.io.IOException("required Kubernetes observation returned no object");
    }
    return json.readTree(output);
  }

  /** Represents kubectl --ignore-not-found absence without a parser-specific sentinel. */
  Optional<JsonNode> find(String... arguments) throws Exception {
    final String output = run("", arguments);
    return output.isBlank() ? Optional.empty() : Optional.of(json.readTree(output));
  }

  void requireControlPlane(Path evidence) throws Exception {
    commands.run(List.of("bash", "scripts/lib/local-kind.sh", "control-plane-stability",
        config.toString(), context, evidence.toString()), "", Duration.ofSeconds(75)).checked();
  }

  void create(JsonNode document) throws Exception {
    run(json.writeValueAsString(document), "create", "-f", "-");
  }

  void waitFor(String condition, String object) throws Exception {
    execute("", Duration.ofMinutes(11), "wait", "--for=" + condition, object,
        "--timeout=600s").checked();
  }
}

package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/** Applies current platform operations and observes worker image-filesystem headroom. */
final class KindHealth {
  private KindHealth() {}

  static void requireStable(KubernetesClient client, Path evidence, String phase) throws Exception {
    client.requireControlPlane(evidence.resolve("control-" + phase));
  }

  static void requireReady(JsonNode object) {
    final boolean ready = java.util.stream.StreamSupport.stream(
        object.path("status").path("conditions").spliterator(), false).anyMatch(condition ->
        "Ready".equals(condition.path("type").asText())
            && "True".equals(condition.path("status").asText()));
    if (!ready) {
      throw new IllegalStateException("Kubernetes object is not Ready: "
          + object.path("metadata").path("name").asText());
    }
  }

  static void requireImageHeadroom(BoundedCommand commands, DockerContext context,
      KubernetesClient client, Path evidence, String phase) throws Exception {
    final var observed = imageFilesystems(commands, context, client);
    Files.write(evidence.resolve("imagefs-" + phase + ".json"),
        new ObjectMapper().writeValueAsBytes(observed));
    requireHeadroom(observed);
  }

  static void requireImageHeadroom(BoundedCommand commands, DockerContext context,
      KubernetesClient client) throws Exception {
    requireHeadroom(imageFilesystems(commands, context, client));
  }

  private static Map<String, Integer> imageFilesystems(BoundedCommand commands,
      DockerContext context, KubernetesClient client) throws Exception {
    final Map<String, Integer> observed = new TreeMap<>();
    for (var worker : client.read("get", "nodes", "-l",
        "simplematch.io/node-pool=local-resilience", "-o", "json").path("items")) {
      final String name = worker.path("metadata").path("name").asText();
      final String output = commands.run(context.command("exec", name, "df", "--output=pcent",
          "/var/lib/containerd"), "", Duration.ofSeconds(15)).checked();
      final int percent = Integer.parseInt(output.lines().skip(1).findFirst().orElseThrow()
          .replace("%", "").trim());
      observed.put(name, percent);
    }
    return Map.copyOf(observed);
  }

  private static void requireHeadroom(Map<String, Integer> observed) {
    if (observed.size() != 3 || observed.values().stream().anyMatch(value -> value >= 75)) {
      throw new IllegalStateException("three workers require image filesystem use below 75%: "
          + observed);
    }
  }
}

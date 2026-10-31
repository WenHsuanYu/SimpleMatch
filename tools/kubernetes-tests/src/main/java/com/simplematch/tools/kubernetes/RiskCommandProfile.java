package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Selects canonical RM-1 resources without altering replication, resources or storage policy. */
record RiskCommandProfile(List<JsonNode> documents) {
  private static final Set<String> OWNERS = Set.of(
      "account-service", "risk-service", "kafka-connect", "kafka", "postgres");
  private static final Set<String> EXTRA = Set.of(
      "ConfigMap/simplematch-platform-config", "ConfigMap/simplematch-kafka-connect-config",
      "Service/kafka-headless", "Job/kafka-topic-provisioning",
      "NetworkPolicy/simplematch-java-services",
      "NetworkPolicy/simplematch-java-services-kubernetes-api",
      "NetworkPolicy/simplematch-kafka-connect");

  RiskCommandProfile {
    documents = List.copyOf(documents);
  }

  static RiskCommandProfile render(Path root, BoundedCommand commands) throws Exception {
    final String rendered = commands.run(List.of("kubectl", "kustomize",
        "--load-restrictor", "LoadRestrictionsNone",
        root.resolve("deploy/k8s/overlays/local").toString()), "", Duration.ofMinutes(1))
        .checked();
    final var yaml = new ObjectMapper(new YAMLFactory());
    final var selected = new ArrayList<JsonNode>();
    try (var parser = yaml.getFactory().createParser(rendered)) {
      while (parser.nextToken() != null) {
        final JsonNode document = yaml.readTree(parser);
        if (includes(document)) {
          selected.add(document);
        }
      }
    }
    return new RiskCommandProfile(selected);
  }

  Set<String> platformImages() {
    final var images = new java.util.TreeSet<String>();
    for (var document : documents) {
      for (String group : List.of("containers", "initContainers")) {
        for (var container : document.path("spec").path("template").path("spec").path(group)) {
          final String image = container.path("image").asText();
          if (!image.startsWith("simplematch/") && !image.startsWith("simplematch-")) {
            images.add(image);
          }
        }
      }
    }
    return Set.copyOf(images);
  }

  private static boolean includes(JsonNode document) {
    final String kind = document.path("kind").asText();
    final String name = document.path("metadata").path("name").asText();
    if (EXTRA.contains(kind + "/" + name)) {
      return true;
    }
    if ("Lease".equals(kind) || "account-service-outbox-connector".equals(name)) {
      return false;
    }
    return OWNERS.contains(name) || OWNERS.stream().anyMatch(owner ->
        Set.of(owner + "-config", owner + "-kraft-config", owner + "-configuration-reader",
            owner + "-outbox-connector", owner + "-flyway").contains(name));
  }
}

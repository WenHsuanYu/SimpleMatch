package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Selects canonical scenario resources without changing their runtime policy. */
record KubernetesProfile(List<JsonNode> documents) {
  private static final Set<String> OWNERS = Set.of(
      "account-service", "risk-service", "kafka-connect", "kafka", "postgres");
  private static final Set<String> EXTRA = Set.of(
      "ConfigMap/simplematch-platform-config", "ConfigMap/simplematch-kafka-connect-config",
      "Service/kafka-headless", "Job/kafka-topic-provisioning",
      "NetworkPolicy/simplematch-java-services",
      "NetworkPolicy/simplematch-java-services-kubernetes-api",
      "NetworkPolicy/simplematch-kafka-connect");

  private enum Scope { RISK_COMMAND, RESTING_BUY }

  private static final Set<String> TRADING_OWNERS = Set.of(
      "account-service", "risk-service", "persistence", "quickfix-gateway", "matching",
      "kafka-connect", "kafka", "postgres", "redis", "market-data-projection",
      "marketdata-streamer");
  private static final Set<String> TRADING_EXTRA = Set.of(
      "Role/matching-partition-lease-holder", "RoleBinding/matching-partition-lease-holder",
      "Service/matching-headless", "Service/quickfix-gateway-headless",
      "Service/quickfix-gateway-owner-0", "ConfigMap/quickfix-gateway-quickfix-config",
      "ConfigMap/quickfix-gateway-fix-spec", "NetworkPolicy/simplematch-fix-ingress");

  KubernetesProfile {
    documents = List.copyOf(documents);
  }

  static KubernetesProfile restingBuy(Path root, BoundedCommand commands) throws Exception {
    return render(root, commands, Scope.RESTING_BUY);
  }

  static KubernetesProfile render(Path root, BoundedCommand commands) throws Exception {
    return render(root, commands, Scope.RISK_COMMAND);
  }

  private static KubernetesProfile render(Path root, BoundedCommand commands, Scope scope)
      throws Exception {
    final String rendered = commands.run(List.of("kubectl", "kustomize",
        "--load-restrictor", "LoadRestrictionsNone",
        root.resolve("deploy/k8s/overlays/local").toString()), "", Duration.ofMinutes(1))
        .checked();
    final var yaml = new ObjectMapper(new YAMLFactory());
    final var selected = new ArrayList<JsonNode>();
    try (var parser = yaml.getFactory().createParser(rendered)) {
      while (parser.nextToken() != null) {
        final JsonNode document = yaml.readTree(parser);
        if (includes(document, scope)) {
          selected.add(document);
        }
      }
    }
    return new KubernetesProfile(selected);
  }

  Set<String> javaServices() {
    return documents.stream().filter(document ->
        Set.of("Deployment", "StatefulSet").contains(document.path("kind").asText()))
        .map(document -> document.path("metadata").path("name").asText())
        .filter(Set.of("account-service", "risk-service",
            "persistence", "quickfix-gateway", "market-data-projection",
            "marketdata-streamer")::contains)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  boolean runsMatching() {
    return documents.stream().anyMatch(document ->
        "StatefulSet".equals(document.path("kind").asText())
            && "matching".equals(document.path("metadata").path("name").asText()));
  }

  /** Keeps schema initialization ahead of service startup for every selected database owner. */
  List<String> migrationJobs() {
    final var services = javaServices();
    return List.of("account-service", "risk-service", "persistence", "quickfix-gateway",
        "market-data-projection")
        .stream().filter(services::contains).map(service -> service + "-flyway").toList();
  }

  Set<String> platformImages() {
    final var images = new java.util.TreeSet<String>();
    for (var document : documents) {
      for (String group : List.of("containers", "initContainers")) {
        for (var container : document.path("spec").path("template").path("spec").path(group)) {
          final String image = container.path("image").asText();
          if (!image.startsWith("simplematch/") && !image.startsWith("simplematch-")
              && !image.equals("quickfix-gateway:local")) {
            images.add(image);
          }
        }
      }
    }
    return Set.copyOf(images);
  }

  private static boolean includes(JsonNode document, Scope scope) {
    final String kind = document.path("kind").asText();
    final String name = document.path("metadata").path("name").asText();
    if (EXTRA.contains(kind + "/" + name)) {
      return true;
    }
    if ("Lease".equals(kind)) {
      return scope == Scope.RESTING_BUY && name.matches("matching-partition-[0-9]{2}");
    }
    if (scope == Scope.RESTING_BUY && TRADING_EXTRA.contains(kind + "/" + name)) {
      return true;
    }
    if (scope == Scope.RISK_COMMAND && "account-service-outbox-connector".equals(name)) {
      return false;
    }
    final Set<String> owners = scope == Scope.RESTING_BUY ? TRADING_OWNERS : OWNERS;
    return owners.contains(name) || owners.stream().anyMatch(owner ->
        Set.of(owner + "-config", owner + "-kraft-config", owner + "-configuration-reader",
            owner + "-outbox-connector", owner + "-flyway").contains(name));
  }
}

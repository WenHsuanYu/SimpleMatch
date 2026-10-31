package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RiskCommandProfileTest {
  @Test
  void rendersTheCanonicalRm1DependenciesWithoutTheUnobservedMatchingFleet() throws Exception {
    final Path root = Path.of(System.getProperty("verificationRepoRoot"));
    final var commands = new BoundedCommand(root, java.util.Map.of());
    final var profile = RiskCommandProfile.render(root, commands);
    final var workloads = profile.documents().stream()
        .filter(document -> Set.of("Deployment", "StatefulSet", "Job")
            .contains(document.path("kind").asText()))
        .map(document -> document.path("kind").asText() + "/"
            + document.path("metadata").path("name").asText())
        .collect(Collectors.toSet());
    assertEquals(Set.of("Deployment/account-service", "Deployment/risk-service",
        "Deployment/kafka-connect", "StatefulSet/kafka",
        "StatefulSet/postgres", "Job/account-service-flyway", "Job/risk-service-flyway",
        "Job/kafka-topic-provisioning"), workloads);
    final var kafka = profile.documents().stream().filter(document ->
        "StatefulSet".equals(document.path("kind").asText())
            && "kafka".equals(document.path("metadata").path("name").asText()))
        .findFirst().orElseThrow();
    assertEquals(3, kafka.path("spec").path("replicas").asInt());
    assertTrue(profile.documents().stream().noneMatch(document ->
        "Lease".equals(document.path("kind").asText())));
  }
}

package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Protects the deployed public-order scenario from omitting its durable business owners. */
class RestingBuyProfileTest {
  @Test
  void selectsTradingOwnersAndMarketDataWithRunOwnedResources() throws Exception {
    final Path root = Path.of(System.getProperty("verificationRepoRoot"));
    final var profile = KubernetesProfile.restingBuy(root, new BoundedCommand(root, java.util.Map.of()));
    final var identities = profile.documents().stream().map(document ->
        document.path("kind").asText() + "/" + document.path("metadata").path("name").asText())
        .collect(Collectors.toSet());
    assertEquals(Set.of("account-service", "risk-service", "persistence", "quickfix-gateway",
        "market-data-projection", "marketdata-streamer"),
        profile.javaServices());
    assertTrue(profile.runsMatching());
    assertTrue(profile.platformImages().stream()
        .noneMatch(image -> image.startsWith("quickfix-gateway:")));
    assertEquals(List.of("account-service-flyway", "risk-service-flyway",
        "persistence-flyway", "quickfix-gateway-flyway", "market-data-projection-flyway"),
        profile.migrationJobs());
    assertTrue(profile.migrationJobs().stream().allMatch(name -> identities.contains("Job/" + name)));
    assertTrue(identities.containsAll(Set.of(
        "Deployment/account-service", "Deployment/risk-service", "Deployment/persistence",
        "StatefulSet/quickfix-gateway", "StatefulSet/matching", "StatefulSet/kafka",
        "StatefulSet/postgres", "Deployment/kafka-connect", "Job/persistence-flyway",
        "Deployment/redis", "Service/redis", "Deployment/market-data-projection",
        "Deployment/marketdata-streamer", "Service/marketdata-streamer",
        "Job/market-data-projection-flyway", "ConfigMap/market-data-projection-config",
        "ConfigMap/marketdata-streamer-config",
        "Job/quickfix-gateway-flyway", "ConfigMap/account-service-outbox-connector",
        "ConfigMap/quickfix-gateway-quickfix-config", "ConfigMap/quickfix-gateway-fix-spec",
        "Role/matching-partition-lease-holder", "RoleBinding/matching-partition-lease-holder",
        "Service/matching-headless", "Service/quickfix-gateway-owner-0",
        "NetworkPolicy/simplematch-fix-ingress")));
    assertEquals(15, identities.stream().filter(value -> value.startsWith("Lease/")).count());
    assertTrue(identities.stream().noneMatch(value -> value.equals("Deployment/query-service")));
  }
}

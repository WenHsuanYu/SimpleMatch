package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Observes R21 against both real Query Pods during the existing run-owned Redis outage. */
final class KubernetesQueryHealth {
  private final KubernetesScenarioTarget target;
  private final KubernetesQueryCredentials credentials;
  private final KubernetesWorkloadImages images;
  private final Path evidence;
  private final ObjectMapper json = new ObjectMapper();
  private List<Owner> baseline;
  private int livenessWindowSeconds;
  private String deploymentUid;

  KubernetesQueryHealth(KubernetesBusinessEnvironment.PublicOrder context) throws Exception {
    target = context.target();
    credentials = java.util.Objects.requireNonNull(context.queryCredentials());
    images = java.util.Objects.requireNonNull(context.workloadImages());
    evidence = Files.createDirectory(target.run().evidence().resolve("query-health"));
  }

  void baseline() throws Exception {
    baseline = capture("baseline", true);
  }

  void redisUnavailable() throws Exception {
    assertRedisStopped();
    assertTrue(livenessWindowSeconds >= 60 && livenessWindowSeconds <= 120);
    Thread.sleep(Duration.ofSeconds(livenessWindowSeconds));
    assertRedisStopped();
    requireStableOwners(baseline, capture("redis-outage", false));
  }

  void redisRestored() throws Exception {
    final var redis = target.client().read("get", "deployment", "redis", "-o", "json");
    assertEquals(target.run().runId(), redis.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    assertEquals(1, redis.path("spec").path("replicas").asInt(-1));
    assertEquals(1, redis.path("status").path("readyReplicas").asInt(-1));
    requireStableOwners(baseline, capture("restored", false));
    Files.write(evidence.resolve("r21-observed.json"), json.writeValueAsBytes(Map.of(
        "baseline", "observed", "redisOutage", "observed", "restoration", "observed",
        "livenessWindowSeconds", livenessWindowSeconds)));
  }

  private List<Owner> capture(String stage, boolean initial) throws Exception {
    final var deployment = target.client().read("get", "deployment", "query-service", "-o", "json");
    assertEquals(target.run().runId(), deployment.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    final String uid = deployment.path("metadata").path("uid").asText();
    assertFalse(uid.isBlank());
    if (initial) {
      deploymentUid = uid;
    } else {
      assertEquals(deploymentUid, uid, "Query Deployment owner changed during the outage");
    }
    assertEquals(2, deployment.path("spec").path("replicas").asInt(-1));
    assertEquals(2, deployment.path("status").path("readyReplicas").asInt(-1));
    assertTrue(deployment.path("status").path("observedGeneration").asLong(-1)
        >= deployment.path("metadata").path("generation").asLong(Long.MAX_VALUE));
    assertEquals("local-resilience", deployment.at(
        "/spec/template/spec/nodeSelector/simplematch.io~1node-pool").asText());
    final var containers = deployment.at("/spec/template/spec/containers");
    assertEquals(1, containers.size());
    final var container = containers.get(0);
    assertEquals("query-service", container.path("name").asText());
    final String image = target.replacements().get("simplematch/query-service:local");
    assertFalse(image == null || image.isBlank(), "run has no Query image identity");
    assertEquals(image, container.path("image").asText(),
        "Query Deployment template differs from the run image");
    assertEquals("/actuator/health/readiness",
        container.at("/readinessProbe/httpGet/path").asText());
    assertEquals("/actuator/health/liveness",
        container.at("/livenessProbe/httpGet/path").asText());
    assertEquals("http", container.at("/readinessProbe/httpGet/port").asText());
    assertEquals("http", container.at("/livenessProbe/httpGet/port").asText());
    final var probe = container.path("livenessProbe");
    final int window = probe.path("initialDelaySeconds").asInt(-1)
        + probe.path("periodSeconds").asInt(-1)
            * (probe.path("failureThreshold").asInt(-1) + 1);
    assertTrue(window >= 60 && window <= 120,
        "Query liveness window must be observed, not guessed");
    if (initial) {
      livenessWindowSeconds = window;
    } else {
      assertEquals(livenessWindowSeconds, window,
          "Query liveness configuration changed during the outage");
    }
    final var pods = target.client().read("get", "pods", "-l",
        "app.kubernetes.io/name=query-service,app.kubernetes.io/component=java-service",
        "-o", "json").required("items");
    assertEquals(2, pods.size(), "both concrete Query replicas must be observed");
    final var owners = new ArrayList<Owner>();
    for (var pod : pods) {
      assertEquals(target.run().runId(), pod.path("metadata").path("labels")
          .path(OwnedNamespace.OWNER_LABEL).asText());
      owners.add(requireOwner(pod, image));
    }
    owners.sort(Comparator.comparing(Owner::name));
    assertFalse(owners.get(0).uid().equals(owners.get(1).uid()));
    assertFalse(owners.get(0).node().equals(owners.get(1).node()),
        "Query replicas must be placed on distinct workers");
    images.verifySelected(target.client(), target.run(), "query-" + stage, "query-service", 2);
    Files.write(evidence.resolve(stage + "-owners.json"), json.writeValueAsBytes(Map.of(
        "deploymentUid", deployment.path("metadata").path("uid").asText(),
        "image", image, "pods", owners, "livenessWindowSeconds", window)));
    for (var owner : owners) {
      probe(stage, owner, initial);
    }
    return List.copyOf(owners);
  }

  static Owner requireOwner(JsonNode pod, String expectedImage) {
    assertTrue(pod.path("metadata").path("deletionTimestamp").isMissingNode(),
        "terminating Query Pod is not healthy");
    assertEquals("Running", pod.path("status").path("phase").asText());
    assertTrue(java.util.stream.StreamSupport.stream(
        pod.at("/status/conditions").spliterator(), false).anyMatch(condition ->
            "Ready".equals(condition.path("type").asText())
                && "True".equals(condition.path("status").asText())));
    final var containers = pod.at("/spec/containers");
    final var statuses = pod.at("/status/containerStatuses");
    assertEquals(1, containers.size());
    assertEquals(1, statuses.size());
    assertEquals("query-service", containers.get(0).path("name").asText());
    assertEquals(expectedImage, containers.get(0).path("image").asText());
    assertEquals("query-service", statuses.get(0).path("name").asText());
    assertTrue(statuses.get(0).path("ready").asBoolean());
    final var owner = new Owner(pod.path("metadata").path("name").asText(),
        pod.path("metadata").path("uid").asText(),
        pod.path("spec").path("nodeName").asText(),
        statuses.get(0).path("imageID").asText(),
        statuses.get(0).path("restartCount").asInt(-1));
    assertFalse(owner.name().isBlank() || owner.uid().isBlank() || owner.node().isBlank()
        || owner.imageId().isBlank());
    assertTrue(owner.restarts() >= 0);
    return owner;
  }

  static void requireStableOwners(List<Owner> initial, List<Owner> observed) {
    assertEquals(initial, observed, "Query Pod UID/node/image/restarts changed during recovery");
  }

  private void probe(String stage, Owner owner, boolean initial) throws Exception {
    try (var forward = target.client().forward("pod/" + owner.name(), 8086,
        evidence.resolve(stage + "-" + owner.name() + "-forward.log"));
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      final ObjectNode observations = json.createObjectNode();
      for (String health : List.of("readiness", "liveness")) {
        final var result = get(http, forward.port(), "/actuator/health/" + health, null);
        final JsonNode body = json.readTree(result.body());
        observations.put(health + "HttpStatus", result.statusCode());
        observations.set(health + "Response", body);
        requireHealth(result.statusCode(), body, health + " for " + owner.name());
      }
      final var serving = get(http, forward.port(), "/api/v1/freshness",
          credentials.operationsToken());
      final String policyVersion = serving.headers().firstValue(
          "X-SimpleMatch-Query-Policy-Version").orElse("");
      final JsonNode freshness = json.readTree(serving.body());
      observations.put("authenticatedFreshnessHttpStatus", serving.statusCode());
      observations.put("policyVersion", policyVersion);
      observations.set("freshnessResponse", freshness);
      requireOperations(serving.statusCode(), policyVersion, freshness);
      if (initial) {
        final int anonymousStatus = get(http, forward.port(),
            "/api/v1/freshness", null).statusCode();
        final int readerStatus = get(http, forward.port(),
            "/api/v1/freshness", credentials.readerToken()).statusCode();
        observations.put("anonymousFreshnessHttpStatus", anonymousStatus);
        observations.put("accountReaderFreshnessHttpStatus", readerStatus);
        assertEquals(401, anonymousStatus,
            "unauthenticated Query operations read must be rejected");
        assertEquals(403, readerStatus,
            "a business-account credential must not gain operations access");
      }
      Files.write(evidence.resolve(stage + "-" + owner.name() + "-serving.json"),
          json.writeValueAsBytes(observations));
    }
  }

  static void requireHealth(int status, JsonNode body, String endpoint) {
    assertEquals(200, status, "Query health endpoint failed: " + endpoint);
    assertEquals("UP", body.path("status").asText(), "Query health body failed: " + endpoint);
  }

  static void requireOperations(int status, String policyVersion, JsonNode body) {
    assertEquals(200, status, "authenticated Query operations read failed");
    assertEquals("1", policyVersion, "Query policy version changed");
    assertTrue(body.path("partitions").isArray(),
        "Query freshness must be an actual PostgreSQL-backed response");
  }

  private static HttpResponse<String> get(HttpClient http, int port, String path, String token)
      throws Exception {
    final var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20)).GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertRedisStopped() throws Exception {
    final var redis = target.client().read("get", "deployment", "redis", "-o", "json");
    final var pods = target.client().read("get", "pods", "-l",
        "app.kubernetes.io/name=redis", "-o", "json").required("items");
    requireRedisStopped(redis, pods, target.run().runId());
  }

  static void requireRedisStopped(JsonNode redis, JsonNode pods, String runId) {
    assertEquals(runId, redis.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    assertEquals(0, redis.path("spec").path("replicas").asInt(-1));
    assertEquals(0, redis.path("status").path("readyReplicas").asInt());
    for (var pod : pods) {
      assertFalse(java.util.stream.StreamSupport.stream(
          pod.at("/status/conditions").spliterator(), false).anyMatch(condition ->
              "Ready".equals(condition.path("type").asText())
                  && "True".equals(condition.path("status").asText())),
          "Redis became Ready during Query outage verification");
    }
  }

  record Owner(String name, String uid, String node, String imageId, int restarts) {}
}

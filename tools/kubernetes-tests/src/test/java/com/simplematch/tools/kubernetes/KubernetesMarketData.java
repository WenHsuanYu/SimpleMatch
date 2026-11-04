package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Uses the current business run and its owned namespace for Market Data replay and restoration. */
final class KubernetesMarketData implements AutoCloseable {
  private static final Duration RESTORE_BUDGET = Duration.ofMinutes(3);
  private final KubernetesScenarioTarget target;
  private final KubernetesPostgres postgres;
  private final Path evidence;
  private final ObjectMapper json = new ObjectMapper();
  private final String operatorToken = UUID.randomUUID().toString().replace("-", "");
  private int originalProjectionReplicas;
  private int originalRedisReplicas;
  private boolean projectionEnvironmentChanged;
  private boolean projectionReplicasChanged;
  private boolean redisReplicasChanged;

  KubernetesMarketData(KubernetesBusinessEnvironment.PublicOrder context) throws Exception {
    target = context.target();
    postgres = new KubernetesPostgres(target);
    evidence = Files.createDirectory(target.run().evidence().resolve("market-data"));
  }

  void verify() throws Exception {
    final var projection = requireDeployment("market-data-projection");
    final var redis = requireDeployment("redis");
    requireDeployment("marketdata-streamer");
    originalProjectionReplicas = projection.required("spec").required("replicas").asInt();
    originalRedisReplicas = redis.required("spec").required("replicas").asInt();
    assertTrue(originalProjectionReplicas > 0, "projection must have a running replica");
    assertEquals(1, originalRedisReplicas, "Redis must have one disposable replica");
    for (var container : projection.path("spec").path("template").path("spec")
        .path("containers")) {
      if (!"market-data-projection".equals(container.path("name").asText())) {
        continue;
      }
      for (var environment : container.path("env")) {
        assertFalse(List.of("SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_HTTP_ENABLED",
            "SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_OPERATOR_TOKEN")
            .contains(environment.path("name").asText()),
            "projection already has an operator replay override");
      }
    }
    requireTopicProfile();
    enableProjection();
    final Instrument instrument = instrument();
    new KubernetesMarketDataRebuild(this).verify(instrument);
    enableProjection();
    new KubernetesMarketDataStreamer(this).verify(instrument);
  }

  private void enableProjection() throws Exception {
    if (originalProjectionReplicas != 1) {
      projectionReplicasChanged = true;
      scale("market-data-projection", 1);
    }
    projectionEnvironmentChanged = true;
    client().run("", "set", "env", "deployment/market-data-projection",
        "SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_HTTP_ENABLED=true",
        "SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_OPERATOR_TOKEN=" + operatorToken);
    ready("market-data-projection", 1);
  }

  /** Checks this run's namespace label before a mutating Kubernetes operation. */
  JsonNode requireDeployment(String name) throws Exception {
    final var deployment = client().read("get", "deployment", name, "-o", "json");
    assertEquals(target.run().runId(), deployment.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText(), "deployment is not run-owned: " + name);
    assertFalse(deployment.path("metadata").path("uid").asText().isBlank());
    if (List.of("market-data-projection", "marketdata-streamer").contains(name)) {
      final String expectedImage = target.replacements().get("simplematch/" + name + ":local");
      assertFalse(expectedImage == null || expectedImage.isBlank(),
          "run has no locked image for " + name);
      final var candidates = java.util.stream.StreamSupport.stream(deployment.path("spec")
          .path("template").path("spec").path("containers").spliterator(), false)
          .filter(container -> name.equals(container.path("name").asText())).toList();
      assertEquals(1, candidates.size(), "expected one workload container for " + name);
      assertEquals(expectedImage, candidates.get(0).path("image").asText(),
          "deployment image differs from the selected run image: " + name);
    }
    return deployment;
  }

  Instrument instrument() throws Exception {
    final var selected = postgres.await("""
        SELECT json_build_object('venueMic', TRIM(venue_mic), 'symbol', symbol)
        FROM market_data_projection.instrument_market_data
        ORDER BY updated_at_unix_ms DESC, venue_mic, symbol LIMIT 1;
        """, java.util.Map.of(), value -> value.path("venueMic").asText().matches("[A-Z0-9]{4}")
            && !value.path("symbol").asText().isBlank(), Duration.ofMinutes(2));
    return new Instrument(selected.required("venueMic").asText(),
        selected.required("symbol").asText());
  }

  /** Replays the existing completed Matching events through the projection's operator reset. */
  void replay(String phase) throws Exception {
    resetProjection(phase);
    restartProjectionFromEarliest(phase);
  }

  void resetProjection(String phase) throws Exception {
    try (var forward = client().forward("service/market-data-projection", 8080,
        evidence.resolve(phase + "-projection-forward.log"))) {
      final var request = HttpRequest.newBuilder(URI.create(
          "http://127.0.0.1:" + forward.port() + "/internal/market-data/rebuild"))
          .header("X-SimpleMatch-Projection-Token", operatorToken)
          .timeout(Duration.ofSeconds(30))
          .POST(HttpRequest.BodyPublishers.noBody()).build();
      try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
        final var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "projection reset must be authorized");
        final var body = json.readTree(response.body());
        assertEquals("RESET_COMPLETE", body.path("status").asText());
        Files.write(evidence.resolve(phase + "-reset.json"), json.writeValueAsBytes(body));
      }
    }
  }

  void restartProjectionFromEarliest(String phase) throws Exception {
    final String broker = broker();
    final String offsets = client().execute("", Duration.ofSeconds(90), "exec", broker,
        "-c", "kafka", "--", "/opt/kafka/bin/kafka-consumer-groups.sh",
        "--bootstrap-server", "kafka:9092", "--group", "market-data-projection",
        "--topic", "matching.events", "--reset-offsets", "--to-earliest", "--execute").checked();
    Files.writeString(evidence.resolve(phase + "-offset-reset.txt"), offsets);
    client().run("", "rollout", "restart", "deployment/market-data-projection");
    ready("market-data-projection", 1);
  }

  private String broker() throws Exception {
    final var brokers = client().read("get", "pods", "-l", "app.kubernetes.io/name=kafka,"
        + "app.kubernetes.io/component=broker," + OwnedNamespace.OWNER_LABEL + "="
        + target.run().runId(), "-o", "json").required("items");
    assertEquals(3, brokers.size(), "Kafka must have three run-owned brokers");
    return brokers.get(0).required("metadata").required("name").asText();
  }

  private void requireTopicProfile() throws Exception {
    final String broker = broker();
    final String topic = client().execute("", Duration.ofSeconds(45), "exec", broker,
        "-c", "kafka", "--", "/opt/kafka/bin/kafka-topics.sh",
        "--bootstrap-server", "kafka:9092", "--describe", "--topic", "marketdata.events")
        .checked();
    final String config = client().execute("", Duration.ofSeconds(45), "exec", broker,
        "-c", "kafka", "--", "/opt/kafka/bin/kafka-configs.sh",
        "--bootstrap-server", "kafka:9092", "--entity-type", "topics",
        "--entity-name", "marketdata.events", "--describe", "--all").checked();
    Files.writeString(evidence.resolve("marketdata-events-topic.txt"), topic);
    Files.writeString(evidence.resolve("marketdata-events-config.txt"), config);
    assertTrue(topic.matches("(?s).*PartitionCount:\\s*15\\b.*"),
        "marketdata.events must have fifteen partitions");
    assertTrue(topic.matches("(?s).*ReplicationFactor:\\s*3\\b.*"),
        "marketdata.events must have three replicas");
    for (String setting : List.of("cleanup.policy=delete", "retention.ms=2592000000",
        "min.insync.replicas=2")) {
      assertTrue(config.contains(setting), "marketdata.events topic setting differs: " + setting);
    }
  }

  void scale(String name, int replicas) throws Exception {
    requireDeployment(name);
    client().run("", "scale", "deployment/" + name, "--replicas=" + replicas);
    ready(name, replicas);
  }

  void stopRedis() throws Exception {
    redisReplicasChanged = true;
    scale("redis", 0);
  }

  void restoreRedis() throws Exception {
    if (redisReplicasChanged) {
      scale("redis", originalRedisReplicas);
      redisReplicasChanged = false;
    }
  }

  void ready(String name, int replicas) throws Exception {
    final var budget = OperationBudget.start(RESTORE_BUDGET);
    while (true) {
      budget.remaining();
      final var deployment = requireDeployment(name);
      final var pods = client().read("get", "pods", "-l", "app.kubernetes.io/name=" + name
          + "," + OwnedNamespace.OWNER_LABEL + "=" + target.run().runId(), "-o", "json")
          .required("items");
      budget.remaining();
      if (deployment.path("spec").path("replicas").asInt(-1) == replicas
          && deployment.path("status").path("observedGeneration").asLong(-1)
              >= deployment.path("metadata").path("generation").asLong(Long.MAX_VALUE)
          && deployment.path("status").path("readyReplicas").asInt() == replicas
          && deployment.path("status").path("replicas").asInt() == replicas
          && pods.size() == replicas) {
        return;
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(500)));
    }
  }

  JsonNode query(String sql) throws Exception {
    return json.readTree(postgres.execute(sql, java.util.Map.of()));
  }

  void save(String name, Object value) throws Exception {
    Files.write(evidence.resolve(name), json.writeValueAsBytes(value));
  }

  KubernetesClient client() {
    return target.client();
  }

  KubernetesScenarioTarget target() {
    return target;
  }

  Path evidence() {
    return evidence;
  }

  /** Removes the temporary operator API and restores the original projection replica count. */
  void restoreProjection() throws Exception {
    Exception failure = null;
    if (projectionEnvironmentChanged) {
      try {
        client().run("", "set", "env", "deployment/market-data-projection",
            "SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_HTTP_ENABLED-",
            "SIMPLEMATCH_MARKET_DATA_PROJECTION_REBUILD_OPERATOR_TOKEN-");
        projectionEnvironmentChanged = false;
      } catch (Exception error) {
        failure = recordFailure(failure, error);
      }
    }
    if (projectionReplicasChanged) {
      try {
        scale("market-data-projection", originalProjectionReplicas);
        projectionReplicasChanged = false;
      } catch (Exception error) {
        failure = recordFailure(failure, error);
      }
    } else if (projectionEnvironmentChanged == false && originalProjectionReplicas > 0) {
      try {
        ready("market-data-projection", originalProjectionReplicas);
      } catch (Exception error) {
        failure = recordFailure(failure, error);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** Restores remaining mutations before VerificationRun handles owned namespace cleanup. */
  @Override
  public void close() throws Exception {
    Exception failure = null;
    if (redisReplicasChanged) {
      try {
        restoreRedis();
      } catch (Exception error) {
        failure = error;
      }
    }
    try {
      restoreProjection();
    } catch (Exception error) {
      failure = recordFailure(failure, error);
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static Exception recordFailure(Exception prior, Exception next) {
    if (prior == null) {
      return next;
    }
    prior.addSuppressed(next);
    return prior;
  }

  record Instrument(String venueMic, String symbol) {}
}

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads one real public FIX Order through Query's authenticated, Kafka-fed HTTP surface.
 * The executed-fill and Query-replay contracts remain separate R18 requirements.
 */
final class KubernetesQueryBusinessRead {
  private static final Duration CONVERGENCE_BUDGET = Duration.ofMinutes(2);
  private final KubernetesScenarioTarget target;
  private final KubernetesQueryCredentials credentials;
  private final KubernetesPostgres postgres;
  private final ObjectMapper json = new ObjectMapper();
  private final Path evidence;
  private final UUID account;
  private final UUID order;
  private final String day;
  private final String venue;
  private final String symbol;
  private JsonNode baseline;

  KubernetesQueryBusinessRead(KubernetesBusinessEnvironment.PublicOrder context, UUID account,
      UUID order, String venue, String symbol) throws Exception {
    target = context.target();
    credentials = context.queryCredentials();
    postgres = new KubernetesPostgres(target);
    evidence = Files.createDirectory(target.run().evidence().resolve("query-business"));
    this.account = account;
    this.order = order;
    day = context.settings().day().toString();
    this.venue = venue;
    this.symbol = symbol;
  }

  void baseline() throws Exception {
    final String statement = """
        SELECT json_build_object(
          'orderCount', (SELECT COUNT(*) FROM query_service.order_read_model
            WHERE order_id = :'order_id' AND account_id = :'account_id'
              AND state = 'CANCELED'),
          'accountCount', (SELECT COUNT(*) FROM query_service.account_summary_read_model
            WHERE account_id = :'account_id'),
          'marketCount', (SELECT COUNT(*) FROM query_service.active_market_reference
            WHERE trading_day = :'day'::date AND venue_mic = :'venue' AND symbol = :'symbol')
        )::text;
        """;
    final var observed = postgres.await(statement, java.util.Map.of(
        "order_id", order.toString(), "account_id", account.toString(), "day", day,
        "venue", venue, "symbol", symbol),
        value -> value.path("orderCount").asInt() == 1
            && value.path("accountCount").asInt() == 1
            && value.path("marketCount").asInt() == 1, CONVERGENCE_BUDGET);
    Files.write(evidence.resolve("source-projection.json"), json.writeValueAsBytes(observed));
    baseline = capture("baseline");
  }

  void redisUnavailable() throws Exception {
    requireBaseline();
    assertEquals(baseline, capture("redis-outage"),
        "Query's durable business view changed during an unrelated Redis outage");
  }

  void redisRestored() throws Exception {
    requireBaseline();
    assertEquals(baseline, capture("restored"),
        "Query's durable business view changed after Redis restoration");
  }

  /** Records post-trading business facts without weakening the earlier Redis-outage baseline. */
  void settledBaseline() throws Exception {
    requireBaseline();
    baseline = capture("settled-before-replay");
  }

  /** Requires a Kafka-backed reconstruction of the settled authenticated business view. */
  void replayed() throws Exception {
    replayed("replayed");
  }

  /** Keeps the operator-enabled and fully restored replay observations independent. */
  void replayed(String phase) throws Exception {
    requireBaseline();
    assertEquals(baseline, capture(phase),
        "replayed Query PostgreSQL rows differ from the previously observed source-backed view");
  }

  private void requireBaseline() {
    if (baseline == null) {
      throw new IllegalStateException("R18 baseline business view was not observed");
    }
  }

  private JsonNode capture(String phase) throws Exception {
    try (var forward = target.client().forward("service/query-service", 8086,
        evidence.resolve(phase + "-forward.log"));
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      final var snapshot = json.createObjectNode();
      snapshot.set("order", read(http, forward.port(),
          "/api/v1/orders/" + order, phase + "-order"));
      snapshot.set("executions", read(http, forward.port(),
          "/api/v1/orders/" + order + "/executions", phase + "-executions"));
      snapshot.set("accountSummary", read(http, forward.port(),
          "/api/v1/accounts/" + account + "/summary", phase + "-account"));
      snapshot.set("marketReference", read(http, forward.port(),
          "/api/v1/market-reference/" + day + "/" + venue + "/" + symbol,
          phase + "-market"));
      final var freshness = read(http, forward.port(), "/api/v1/freshness",
          credentials.operationsToken(), phase + "-freshness");
      snapshot.set("freshness", freshness);
      Files.write(evidence.resolve(phase + ".json"), json.writeValueAsBytes(snapshot));
      verify(snapshot);
      return businessView(snapshot);
    }
  }

  private JsonNode read(HttpClient http, int port, String path, String evidenceName)
      throws Exception {
    return read(http, port, path, credentials.readerToken(), evidenceName);
  }

  private JsonNode read(HttpClient http, int port, String path, String token,
      String evidenceName) throws Exception {
    final var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Bearer " + token)
        .GET().build();
    final var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    final String policyVersion = response.headers().firstValue(
        "X-SimpleMatch-Query-Policy-Version").orElse("");
    try {
      assertEquals(200, response.statusCode(), "authorized deployed Query read failed: " + path);
      assertEquals("1", policyVersion, "deployed Query policy changed without authorization");
      final JsonNode body = json.readTree(response.body());
      assertTrue(body != null && body.isObject(), "Query must return a JSON object: " + path);
      return body;
    } catch (Exception | AssertionError failure) {
      final String redacted = DiagnosticText.redact(response.body());
      Files.write(evidence.resolve(evidenceName + "-http-failure.json"),
          json.writeValueAsBytes(java.util.Map.of(
              "status", response.statusCode(), "policyVersion", policyVersion,
              "responseExcerpt", redacted.substring(0, Math.min(4096, redacted.length())))));
      throw failure;
    }
  }

  private void verify(JsonNode snapshot) {
    final JsonNode orderData = envelope(snapshot, "order", false);
    assertEquals(order.toString(), orderData.path("orderId").asText());
    assertEquals(account.toString(), orderData.path("accountId").asText());
    assertEquals(venue, orderData.path("venueMic").asText());
    assertEquals(symbol, orderData.path("symbol").asText());
    // The prior R15 public FIX cancel must reach Query before the Redis baseline.
    assertEquals("CANCELED", orderData.path("state").asText());
    assertFalse(orderData.path("lastEventId").asText().isBlank(),
        "Query Order must retain an actual source Matching event");
    final JsonNode executions = envelope(snapshot, "executions", true);
    for (var execution : executions) {
      assertEquals(order.toString(), execution.path("orderId").asText());
      assertEquals(account.toString(), execution.path("accountId").asText());
    }
    assertEquals(account.toString(),
        envelope(snapshot, "accountSummary", false).path("accountId").asText());
    final JsonNode market = envelope(snapshot, "marketReference", false);
    assertEquals(day, market.path("tradingDay").asText());
    assertEquals(venue, market.path("venueMic").asText());
    assertEquals(symbol, market.path("symbol").asText());
    assertFalse(market.path("artifactId").asText().isBlank());
    final var partitions = snapshot.path("freshness").path("partitions");
    assertTrue(partitions.isArray() && !partitions.isEmpty());
    final Set<String> topics =
        java.util.stream.StreamSupport.stream(partitions.spliterator(), false)
            .map(partition -> partition.path("sourceTopic").asText())
            .collect(Collectors.toSet());
    assertTrue(topics.containsAll(Set.of("matching.events", "account.lifecycle")),
        "Query freshness must include Matching and Account lifecycle checkpoints");
    for (var partition : partitions) {
      assertEquals("READY", partition.path("recoveryState").asText());
      assertTrue(partition.path("lastProcessedOffset").asLong(-1) >= 0);
    }
  }

  private static JsonNode envelope(JsonNode snapshot, String name, boolean list) {
    final JsonNode value = snapshot.path(name);
    assertTrue(value.isObject() && value.has("data") && value.has("freshness"),
        "Query business envelope is incomplete: " + name);
    assertTrue(value.path("freshness").isObject());
    assertTrue(list ? value.path("data").isArray() : value.path("data").isObject());
    return value.path("data");
  }

  private JsonNode businessView(JsonNode source) {
    final ObjectNode normalized = json.createObjectNode();
    for (String name : List.of("order", "executions", "accountSummary", "marketReference")) {
      final var data = source.path(name).path("data").deepCopy();
      if (data instanceof ObjectNode object) {
        object.remove("updatedAtUnixMs");
      } else if (data.isArray()) {
        for (var item : data) {
          if (item instanceof ObjectNode object) {
            object.remove("executedAtUnixMs");
          }
        }
      }
      normalized.set(name, data);
    }
    return normalized;
  }
}

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Executes the complete five-Principal C/Y/D policy matrix against real source-backed FIX
 * Account/Order/Execution/Market resources, independently for each selected Query Pod.
 */
final class KubernetesQueryPermissionMatrix {
  static final List<String> ALIASES =
      List.of("client-a", "client-b", "client-c", "operations", "client-d");
  private static final Set<String> BUSINESS_ENVELOPE = Set.of("data", "freshness");
  private static final Set<String> FRESHNESS_ENVELOPE = Set.of("projectionState");
  private static final Set<String> STATES =
      Set.of("UNKNOWN", "RECOVERY_REQUIRED", "NO_GAP_RECORDED");
  private static final Set<String> CHECKPOINT_KEYS = Set.of(
      "sourceTopic", "partition", "lastProcessedOffset", "recoveryState", "updatedAtUnixMs");
  private static final int MAX_BODY_CHARS = 262_144;
  private final ObjectMapper json = new ObjectMapper();
  private final Resources resources;

  KubernetesQueryPermissionMatrix(Resources resources) {
    this.resources = resources;
  }

  record OrderFixture(String account, UUID order, boolean hasExecutions) {
    OrderFixture {
      if (!Set.of("X", "Y").contains(account) || order == null) {
        throw new IllegalArgumentException("Query Order matrix fixture needs an actual owner");
      }
    }
  }

  record Resources(UUID accountX, UUID accountY, List<OrderFixture> orders,
      UUID missingOrder, String tradingDay, String venue, String symbol, String artifactId,
      Map<String, Long> reservedNotional) {
    Resources {
      if (accountX == null || accountY == null || accountX.equals(accountY)
          || missingOrder == null || orders == null || orders.size() != 4
          || tradingDay == null || venue == null || symbol == null || artifactId == null
          || artifactId.isBlank() || reservedNotional == null
          || !reservedNotional.keySet().equals(Set.of("X", "Y"))) {
        throw new IllegalArgumentException("full Query matrix requires two distinct real Accounts");
      }
      orders = List.copyOf(orders);
      reservedNotional = Map.copyOf(reservedNotional);
      final var seen = new HashSet<UUID>();
      for (var row : orders) {
        if (!seen.add(row.order()) || missingOrder.equals(row.order())) {
          throw new IllegalArgumentException("Query matrix Order identities overlap");
        }
      }
      for (String account : List.of("X", "Y")) {
        assertEquals(1, orders.stream().filter(row ->
            account.equals(row.account()) && row.hasExecutions()).count());
        assertEquals(1, orders.stream().filter(row ->
            account.equals(row.account()) && !row.hasExecutions()).count());
      }
    }

    UUID account(String alias) {
      return switch (alias) {
        case "X" -> accountX;
        case "Y" -> accountY;
        default -> throw new IllegalArgumentException("unknown Query matrix Account");
      };
    }

    String marketPath() {
      return "/api/v1/market-reference/" + tradingDay + "/" + venue + "/" + symbol;
    }
  }

  record Observation(String pod, String podUid, int version, String principal,
      String account, String resource, String orderId, int status) {}

  static int expected(int version, String principal, String account, String resource) {
    if (version < 1 || version > 3 || !ALIASES.contains(principal)) {
      throw new IllegalArgumentException("unknown Query policy generation or Principal");
    }
    if (version == 3 && "client-d".equals(principal)) {
      return 401;
    }
    if ("freshness".equals(resource)) {
      return "operations".equals(principal) ? 200 : 403;
    }
    if ("market".equals(resource)) {
      return 200;
    }
    if ("missing-parent".equals(resource)) {
      return 404;
    }
    if (!Set.of("account", "order", "executions").contains(resource)
        || !Set.of("X", "Y").contains(account)) {
      throw new IllegalArgumentException("unknown Account-scoped Query matrix route");
    }
    final boolean permitted = switch (principal) {
      case "client-a" -> "X".equals(account);
      case "client-b" -> "Y".equals(account);
      case "client-c" -> "X".equals(account) || version == 1;
      case "operations", "client-d" -> false;
      default -> throw new IllegalArgumentException("unknown Query Principal");
    };
    return permitted ? 200 : 404;
  }

  List<Observation> verify(String pod, String podUid, int version,
      Map<String, String> tokens, HttpClient client, int port) throws Exception {
    assertEquals(Set.copyOf(ALIASES), tokens.keySet(),
        "full matrix must use five named Principals with independent credentials");
    assertEquals(5, new HashSet<>(tokens.values()).size(),
        "five Query Principals must not share credentials");
    final var results = new ArrayList<Observation>();
    for (String principal : ALIASES) {
      final String token = tokens.get(principal);
      assertTrue(token != null && token.matches("[A-Za-z0-9_-]{43}"));
      for (String account : List.of("X", "Y")) {
        check(results, pod, podUid, version, principal, account, "account", "",
            "/api/v1/accounts/" + resources.account(account) + "/summary", token, client, port,
            null);
        for (var order : resources.orders()) {
          if (!account.equals(order.account())) {
            continue;
          }
          final String path = "/api/v1/orders/" + order.order();
          check(results, pod, podUid, version, principal, account, "order",
              order.order().toString(), path, token, client, port, null);
          check(results, pod, podUid, version, principal, account, "executions",
              order.order().toString(), path + "/executions", token, client, port,
              order.hasExecutions());
        }
      }
      final String missing = "/api/v1/orders/" + resources.missingOrder();
      check(results, pod, podUid, version, principal, "none", "missing-parent",
          resources.missingOrder().toString(), missing, token, client, port, null);
      check(results, pod, podUid, version, principal, "none", "missing-parent",
          resources.missingOrder().toString(), missing + "/executions", token, client, port, null);
      check(results, pod, podUid, version, principal, "none", "freshness", "",
          "/api/v1/freshness", token, client, port, null);
      check(results, pod, podUid, version, principal, "none", "market", "",
          resources.marketPath(), token, client, port, null);
    }
    assertEquals(70, results.size(), "complete Query matrix must exercise all 70 HTTP cells");
    return List.copyOf(results);
  }

  private void check(List<Observation> results, String pod, String uid, int version,
      String principal, String account, String resource, String orderId,
      String path, String token, HttpClient client, int port, Boolean hasExecutions)
      throws Exception {
    final var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token).GET().build();
    final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    final int status = expected(version, principal, account, resource);
    assertEquals(status, response.statusCode(),
        "Query authorization differs from the five-Principal matrix: pod=" + pod
            + " v=" + version + " principal=" + principal + " resource=" + resource
            + " account=" + account + " order=" + orderId);
    final String body = response.body();
    assertTrue(body.length() <= MAX_BODY_CHARS, "Query matrix HTTP response exceeds budget");
    if (status != 401) {
      assertEquals(Integer.toString(version), response.headers().firstValue(
          "X-SimpleMatch-Query-Policy-Version").orElse(""),
          "effective Query policy differs from owner generation");
    }
    if (status == 401 || status == 404) {
      assertTrue(body.isEmpty(), "a denied Query resource disclosed a response body");
    }
    if (status == 200) {
      assertTrue(response.headers().allValues("Cache-Control").stream()
          .flatMap(value -> List.of(value.split(",")).stream())
          .anyMatch(value -> "no-store".equalsIgnoreCase(value.trim())),
          "protected Query response omitted no-store");
      final JsonNode decoded = json.readTree(body);
      if ("freshness".equals(resource)) {
        verifyFreshness(decoded);
      } else {
        verifyBusiness(decoded, account, resource, orderId, hasExecutions);
      }
    }
    results.add(new Observation(pod, uid, version, principal, account, resource, orderId, status));
  }

  private void verifyFreshness(JsonNode decoded) {
    assertTrue(decoded.isObject() && decoded.size() == 1 && decoded.path("partitions").isArray(),
        "Query operations response is not the complete checkpoint envelope");
    final var partitions = decoded.path("partitions");
    assertFalse(partitions.isEmpty());
    final var topics = new HashSet<String>();
    for (var row : partitions) {
      assertTrue(row.isObject() && fields(row).equals(CHECKPOINT_KEYS));
      assertEquals("READY", row.path("recoveryState").asText());
      assertTrue(row.path("partition").asInt(-1) >= 0);
      assertTrue(row.path("lastProcessedOffset").asLong(-1) >= 0);
      topics.add(row.path("sourceTopic").asText());
    }
    assertTrue(topics.containsAll(Set.of("matching.events", "account.lifecycle")));
  }

  private void verifyBusiness(JsonNode envelope, String account, String resource,
      String orderId, Boolean hasExecutions) {
    assertTrue(envelope.isObject() && fields(envelope).equals(BUSINESS_ENVELOPE));
    final JsonNode freshness = envelope.path("freshness");
    assertTrue(freshness.isObject() && fields(freshness).equals(FRESHNESS_ENVELOPE));
    assertTrue(STATES.contains(freshness.path("projectionState").asText()));
    final JsonNode data = envelope.path("data");
    if ("executions".equals(resource)) {
      assertTrue(data.isArray());
      assertEquals(hasExecutions, !data.isEmpty(),
          "source-backed Query Executions presence differs from real FIX evidence");
      for (var row : data) {
        verifyIdentity(row, account, orderId);
        assertTrue(row.path("fillQuantityShares").asLong(-1) > 0);
      }
      return;
    }
    assertTrue(data.isObject());
    if ("market".equals(resource)) {
      assertEquals(resources.tradingDay(), data.path("tradingDay").asText());
      assertEquals(resources.venue(), data.path("venueMic").asText());
      assertEquals(resources.symbol(), data.path("symbol").asText());
      assertEquals(resources.artifactId(), data.path("artifactId").asText());
      return;
    }
    verifyIdentity(data, account, orderId);
    if ("account".equals(resource)) {
      assertEquals(resources.reservedNotional().get(account).longValue(),
          data.path("reservedNotionalUnits").asLong(-1),
          "authorized Account projection differs from durable source facts");
    }
    if ("order".equals(resource)) {
      assertEquals(ordersWithExecutions(orderId) ? "FILLED" : "CANCELED",
          data.path("state").asText(), "Query Order state differs from real FIX source");
    }
  }

  private boolean ordersWithExecutions(String orderId) {
    return resources.orders().stream().anyMatch(row ->
        row.order().toString().equals(orderId) && row.hasExecutions());
  }

  private void verifyIdentity(JsonNode row, String account, String orderId) {
    assertTrue(row.isObject(), "Query resource has no typed business identity");
    assertEquals(resources.account(account).toString(), row.path("accountId").asText());
    if (!orderId.isEmpty()) {
      assertEquals(orderId, row.path("orderId").asText());
    }
  }

  private static Set<String> fields(JsonNode node) {
    final var names = new HashSet<String>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}

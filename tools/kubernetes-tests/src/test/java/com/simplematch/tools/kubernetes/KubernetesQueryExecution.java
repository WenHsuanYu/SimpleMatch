package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplematch.quickfixgateway.fix.PreparedFixSubmission;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Verifies that an authenticated Query execution originated from two real public FIX orders. */
final class KubernetesQueryExecution {
  private static final Duration BUDGET = Duration.ofMinutes(2);
  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final KubernetesFixCredentials credentials;
  private final KubernetesPostgres postgres;
  private final KubernetesMatchingEvents events;
  private final RestingBuyKubernetesTest.Order selected;
  private final UUID seller;
  private final ObjectMapper json = new ObjectMapper();
  private final Path evidence;
  private JsonNode baseline;
  private java.math.BigDecimal utilizedNotional;
  private String buyOrderId;

  KubernetesQueryExecution(KubernetesBusinessEnvironment.PublicOrder context,
      KubernetesFixCredentials credentials, KubernetesMatchingEvents events,
      RestingBuyKubernetesTest.Order selected, UUID seller) throws Exception {
    this.context = context;
    this.credentials = credentials;
    this.events = events;
    this.selected = selected;
    this.seller = seller;
    assertFalse(selected.account().equals(seller), "the cross needs independent accounts");
    postgres = new KubernetesPostgres(context.target());
    evidence = Files.createDirectory(context.target().run().evidence().resolve("query-execution"));
  }

  void verify() throws Exception {
    final var values = Map.of("account_id", seller.toString(),
        "trading_day", context.settings().day().toString(),
        "now_ms", Long.toString(System.currentTimeMillis()));
    // Only the newly generated seller is seeded; the existing X buyer belongs to the
    // already admitted public FIX session, and its authoritative limit is left intact.
    postgres.execute(Files.readString(context.settings().root().resolve(
        "scripts/end-to-end/critical-consumers/sql/account-limit-fixture.sql")), values);
    postgres.execute("""
        INSERT INTO account_service.account_positions (
          account_id, symbol, long_qty, short_qty, reserved_long_qty,
          reserved_short_qty, updated_at_unix_ms, version
        ) VALUES (:'account_id'::uuid, :'symbol', :quantity, 0, 0, 0, :now_ms, 0);
        """, Map.of("account_id", seller.toString(),
            "symbol", selected.instrument().instrument().symbol(),
            "quantity", Long.toString(selected.rule().boardLotShares()),
            "now_ms", Long.toString(System.currentTimeMillis())));

    final var buy = submit("buy", selected.account(), "1", "RESTING");
    buyOrderId = buy.path("orderId").asText();
    final var sell = submit("sell", seller, "2", "FILLED");
    assertFalse(buyOrderId.equals(sell.path("orderId").asText()));
    final var buyFinal = awaitFinal(buyOrderId, selected.account(), "FILLED");
    final var sellFinal = awaitFinal(sell.path("orderId").asText(), seller, "FILLED");
    utilizedNotional = new java.math.BigDecimal(postgres.execute("""
        SELECT utilized_notional::text FROM account_service.account_limits
        WHERE account_id = :'account_id'::uuid
          AND trading_day = :'trading_day'::date AND currency = 'TWD';
        """, Map.of("account_id", selected.account().toString(),
            "trading_day", context.settings().day().toString())).strip());
    assertTrue(utilizedNotional.signum() > 0,
        "a real execution-backed BUY must utilize authoritative Account limit");
    Files.write(evidence.resolve("cross-final.json"), json.writeValueAsBytes(Map.of(
        "fixtureType", "PUBLIC_FIX_CROSS", "buyAdmission", buy, "sellAdmission", sell,
        "buyDurable", buyFinal, "sellDurable", sellFinal)));
    final var projected = postgres.await("""
        SELECT json_build_object(
          'orderCount', (SELECT count(*) FROM query_service.order_read_model
            WHERE order_id = :'order_id' AND state = 'FILLED'),
          'executionCount', (SELECT count(*) FROM query_service.execution_read_model
            WHERE order_id = :'order_id'))::text;
        """, Map.of("order_id", buyOrderId),
        observed -> observed.path("orderCount").asInt() == 1
            && observed.path("executionCount").asInt() > 0, BUDGET);
    Files.write(evidence.resolve("query-source-projection.json"), json.writeValueAsBytes(projected));
    baseline = capture("cross-baseline");
  }

  java.math.BigDecimal utilizedNotional() {
    if (utilizedNotional == null) {
      throw new IllegalStateException("no real R18 Account limit utilization was observed");
    }
    return utilizedNotional;
  }

  void settledBaseline() throws Exception {
    if (baseline == null) {
      throw new IllegalStateException("the R18 execution baseline was never observed");
    }
    baseline = capture("cross-settled-before-replay");
  }

  String buyOrderId() {
    if (baseline == null) {
      throw new IllegalStateException("R18 execution-backed business evidence was never captured");
    }
    return buyOrderId;
  }

  void replayed() throws Exception {
    if (baseline == null) {
      throw new IllegalStateException("R18 execution-backed baseline was not observed");
    }
    assertEquals(baseline, capture("cross-replayed"),
        "Query replay changed a source-backed, nonempty execution business view");
  }

  private JsonNode submit(String phase, UUID account, String side, String expectedStatus)
      throws Exception {
    final var path = Files.createDirectory(evidence.resolve(phase));
    final String clOrdId = "R18-" + phase + "-" + context.target().run().runId();
    final var order = new RestingBuyKubernetesTest.Order(account, clOrdId,
        selected.instrument(), selected.rule(), selected.route());
    final var variables = new HashMap<>(Map.of(
        "account_id", account.toString(), "cl_ord_id", clOrdId,
        "trading_day", context.settings().day().toString()));
    try (var forward = context.target().client().forward(
            "pod/" + context.target().gatewayPod(), 5001, path.resolve("forward.log"));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var env = new HashMap<>(
          RestingBuyKubernetesTest.clientEnvironment(path, credentials, order, forward.port()));
      env.put("SIMPLEMATCH_LIVE_FIX_SIDE", side);
      env.put("SIMPLEMATCH_LIVE_FIX_TIME_IN_FORCE", "0");
      // The same public FIX SessionID must retain its actual message sequence across
      // the original cancel, R18 BUY/SELL, IOC and later delayed order.
      env.put("SIMPLEMATCH_RETAINED_FIX_STATE_DIR", context.target().run().evidence()
          .resolve("fix/client-state").toString());
      env.put("SIMPLEMATCH_RETAINED_FIX_RECOVERY", "false");
      env.put("SIMPLEMATCH_RETAINED_FIX_CANCEL", "false");
      final var client = new PreparedFixSubmission() {
        @Override
        protected String environmentValue(String name) {
          return env.get(name);
        }
      };
      final Future<?> finished = executor.submit(() -> {
        client.runPreparedSubmission();
        return null;
      });
      try {
        RestingBuyKubernetesTest.awaitFile(path.resolve("ready"), finished);
        context.admission().open(context.target(), "r18-" + phase);
        final var before = events.positions();
        Files.write(path.resolve("positions-before.json"), json.writeValueAsBytes(before));
        Files.writeString(path.resolve("release"), "release\n");
        RestingBuyKubernetesTest.awaitFile(path.resolve("receipt.json"), finished);
        final var receipt = json.readTree(path.resolve("receipt.json").toFile());
        assertEquals(account.toString(), receipt.path("accountId").asText());
        final var admission = postgres.await(Files.readString(context.settings().root().resolve(
            "scripts/end-to-end/critical-consumers/sql/resting-buy-admission.sql")),
            variables, value -> "ACCEPTED".equals(value.path("state").asText()), BUDGET);
        assertEquals(account.toString(), admission.path("accountId").asText());
        assertEquals(clOrdId, admission.path("clOrdId").asText());
        assertEquals(1, admission.path("count").asInt());
        assertEquals(1, admission.path("outboxCount").asInt());
        assertEquals(side.equals("1") ? "BUY" : "SELL", admission.path("side").asText());
        awaitPersistence(admission.path("orderId").asText(), expectedStatus);
        final var delivery = postgres.await("""
            SELECT json_build_object('execId', exec_id, 'eventId', encode(event_id, 'hex'),
              'status', status, 'sourcePartition', source_partition,
              'sourceOffset', source_offset)::text
            FROM quickfix_gateway.fix_delivery_intents
            WHERE recipient_order_id = :'order_id'::uuid AND status = 'SENT'
            ORDER BY source_offset DESC LIMIT 1;
            """, Map.of("order_id", admission.path("orderId").asText()),
            value -> "SENT".equals(value.path("status").asText()), BUDGET);
        Files.writeString(path.resolve("complete.next"), delivery.path("execId").asText());
        Files.move(path.resolve("complete.next"), path.resolve("complete"),
            StandardCopyOption.ATOMIC_MOVE);
        RestingBuyKubernetesTest.awaitFile(path.resolve("completed.json"), finished);
        finished.get(60, TimeUnit.SECONDS);
        final int partition = admission.path("routingPartition").asInt(-1);
        final long starting = before.path("matchingEvents").path("partitions")
            .get(partition).path("offset").asLong(-1);
        assertTrue(starting >= 0, "actual Kafka source starting offset is missing");
        final var matched = events.observeAccepted(admission, starting, "r18-" + phase);
        assertEquals(admission.path("orderId"), matched.path("orderId"));
        assertEquals(admission.path("commandId"), matched.path("sourceCommandId"));
        Files.write(path.resolve("admission.json"), json.writeValueAsBytes(admission));
        Files.write(path.resolve("delivery.json"), json.writeValueAsBytes(delivery));
        return admission;
      } finally {
        finished.cancel(true);
      }
    }
  }

  private JsonNode awaitPersistence(String orderId, String state) throws Exception {
    return postgres.await("""
        SELECT json_build_object('orderId', order_id, 'status', status,
          'filled', cumulative_quantity_shares, 'leaves', leaves_quantity_shares,
          'eventId', encode(last_event_id, 'hex'),
          'fillCount', (SELECT count(*) FROM persistence.order_fills
            WHERE order_id = :'order_id'::uuid),
          'reservation', (SELECT status FROM account_service.account_reservations
            WHERE order_id = :'order_id'::uuid))::text
        FROM persistence.matching_order_projections WHERE order_id = :'order_id'::uuid;
        """, Map.of("order_id", orderId),
        value -> state.equals(value.path("status").asText()), BUDGET);
  }

  private JsonNode awaitFinal(String orderId, UUID account, String state) throws Exception {
    final var result = postgres.await("""
        SELECT json_build_object('orderId', p.order_id, 'accountId', p.account_id,
          'status', p.status, 'filled', p.cumulative_quantity_shares,
          'fillCount', (SELECT count(*) FROM persistence.order_fills
            WHERE order_id = p.order_id), 'reservation', r.status,
          'inboxCount', (SELECT count(*) FROM persistence.matching_event_inbox
            WHERE event_id = p.last_event_id),
          'accountInboxCount', (SELECT count(*) FROM account_service.matching_event_inbox
            WHERE event_id = p.last_event_id),
          'fixInboxCount', (SELECT count(*) FROM quickfix_gateway.matching_event_inbox
            WHERE event_id = p.last_event_id),
          'quarantineCount', (SELECT count(*) FROM persistence.matching_consumer_quarantines
            WHERE status = 'QUARANTINED') +
            (SELECT count(*) FROM account_service.matching_event_consumer_quarantines
            WHERE status = 'QUARANTINED') +
            (SELECT count(*) FROM quickfix_gateway.matching_consumer_quarantines
            WHERE status = 'QUARANTINED'))::text
        FROM persistence.matching_order_projections p
        JOIN account_service.account_reservations r ON r.order_id = p.order_id
        WHERE p.order_id = :'order_id'::uuid AND p.account_id = :'account_id'::uuid;
        """, Map.of("order_id", orderId, "account_id", account.toString()),
        value -> state.equals(value.path("status").asText())
            && "RESERVATION_STATUS_APPLIED".equals(value.path("reservation").asText())
            && value.path("fillCount").asInt() > 0
            && value.path("inboxCount").asInt() == 1
            && value.path("accountInboxCount").asInt() == 1
            && value.path("fixInboxCount").asInt() == 1, BUDGET);
    assertEquals(0, result.path("quarantineCount").asInt(-1));
    assertEquals(selected.rule().boardLotShares(), result.path("filled").asLong(-1));
    return result;
  }

  private JsonNode capture(String phase) throws Exception {
    final var view = json.createObjectNode();
    final var target = context.target();
    try (var forward = target.client().forward("service/query-service", 8086,
            evidence.resolve(phase + "-forward.log"));
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      final String prefix = "http://127.0.0.1:" + forward.port();
      final String token = context.queryCredentials().readerToken();
      for (String suffix : List.of("/api/v1/orders/" + buyOrderId,
          "/api/v1/orders/" + buyOrderId + "/executions",
          "/api/v1/accounts/" + selected.account() + "/summary")) {
        final var request = HttpRequest.newBuilder(URI.create(prefix + suffix))
            .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
            .GET().build();
        final var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Query execution view is unavailable");
        assertEquals("1", response.headers().firstValue(
            "X-SimpleMatch-Query-Policy-Version").orElse(""));
        final var body = json.readTree(response.body());
        assertTrue(body.path("data").isObject() || body.path("data").isArray());
        final JsonNode data = body.path("data").deepCopy();
        if (data instanceof ObjectNode object) {
          object.remove("updatedAtUnixMs");
        } else {
          for (var item : data) {
            if (item instanceof ObjectNode object) {
              object.remove("executedAtUnixMs");
            }
          }
        }
        view.set(suffix.substring(suffix.lastIndexOf('/') + 1), data);
      }
    }
    final var order = view.path(buyOrderId);
    assertEquals("FILLED", order.path("state").asText());
    assertEquals(selected.account().toString(), order.path("accountId").asText());
    final var executions = view.path("executions");
    assertTrue(executions.isArray() && !executions.isEmpty(),
        "R18 requires source-backed nonempty Query Executions");
    for (var item : executions) {
      assertEquals(buyOrderId, item.path("orderId").asText());
      assertEquals(selected.account().toString(), item.path("accountId").asText());
      assertTrue(item.path("fillQuantityShares").asLong() > 0);
    }
    Files.write(evidence.resolve(phase + ".json"), json.writeValueAsBytes(view));
    return view;
  }
}

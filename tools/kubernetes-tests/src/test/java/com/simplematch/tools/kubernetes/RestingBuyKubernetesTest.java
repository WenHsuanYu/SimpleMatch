package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.ArtifactInstrument;
import com.simplematch.marketreference.InstrumentEligibility;
import com.simplematch.marketreference.MarketRule;
import com.simplematch.marketreference.RoutingAssignment;
import com.simplematch.quickfixgateway.fix.PreparedFixSubmission;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies one real public BUY across FIX, Risk/CDC, Matching, Persistence, and Account. */
@Tag("kubernetes-live")
class RestingBuyKubernetesTest {
  private final ObjectMapper json = new ObjectMapper();
  @TempDir Path privateFiles;

  @Test
  void publicBuyRestsOnceAndReturnsItsDurableMatchingDelivery() throws Exception {
    final UUID account = UUID.randomUUID();
    try (var credentials = KubernetesFixCredentials.create(privateFiles.resolve("fix"), account)) {
      final var environment = new KubernetesBusinessEnvironment(credentials);
      VerificationRun.verify(environment.request(), environment, run -> {
        try (credentials) {
          verify(environment.publicOrder(), credentials, account);
          environment.verifyRuntime(run);
        }
      });
    }
  }

  private void verify(KubernetesBusinessEnvironment.PublicOrder context,
      KubernetesFixCredentials credentials, UUID account) throws Exception {
    final var target = context.target();
    final var postgres = new KubernetesPostgres(target);
    final var order = order(context, account);
    final var variables = Map.of("account_id", account.toString(),
        "trading_day", context.settings().day().toString(), "cl_ord_id", order.clOrdId(),
        "now_ms", Long.toString(System.currentTimeMillis()));
    postgres.execute(resource("/risk-command/account-buy.sql"), variables);
    Files.writeString(target.run().evidence().resolve("public-order.json"),
        json.writeValueAsString(order));
    context.admission().open(target);
    final Path fix = Files.createDirectory(target.run().evidence().resolve("fix"));
    try (var forward = target.client().forward("pod/" + target.gatewayPod(), 5001,
        fix.resolve("forward.log"));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var environment = clientEnvironment(fix, credentials, order, forward.port());
      final var client = new PreparedFixSubmission() {
        @Override
        protected String environmentValue(String name) {
          return environment.get(name);
        }
      };
      final Future<?> finished = executor.submit(() -> {
        client.runPreparedSubmission();
        return null;
      });
      try {
        awaitFile(fix.resolve("ready"), finished);
        Files.writeString(fix.resolve("release"), "release\n");
        awaitFile(fix.resolve("receipt.json"), finished);
        final var receipt = json.readTree(fix.resolve("receipt.json").toFile());
        assertEquals("A", receipt.path("execType").asText());
        final var admission = awaitSql(postgres, legacy(context, "resting-buy-admission.sql"),
            variables, value -> "ACCEPTED".equals(value.path("state").asText()));
        final var delivery = awaitSql(postgres, resource("/resting-buy/gateway-delivery.sql"),
            variables, value -> "SENT".equals(value.path("status").asText()));
        final var durableVariables = new HashMap<>(variables);
        durableVariables.put("order_id", admission.path("orderId").asText());
        durableVariables.put("event_id", delivery.path("eventId").asText());
        final var durable = awaitSql(postgres, legacy(context, "resting-buy-durable-state.sql"),
            durableVariables, value -> value.path("events").path("accountCount").asInt() > 0
                && value.path("events").path("persistenceCount").asInt() > 0);
        Files.writeString(target.run().evidence().resolve("admission.json"), admission.toPrettyString());
        Files.writeString(target.run().evidence().resolve("durable.json"), durable.toPrettyString());
        Files.writeString(target.run().evidence().resolve("delivery.json"), delivery.toPrettyString());
        Files.writeString(fix.resolve("complete"), delivery.path("execId").asText());
        finished.get(60, TimeUnit.SECONDS);
        final var completed = json.readTree(fix.resolve("completed.json").toFile());
        assertFacts(context, order, admission, delivery, durable, completed);
      } finally {
        finished.cancel(true);
      }
    }
  }

  private void assertFacts(KubernetesBusinessEnvironment.PublicOrder context, Order order,
      JsonNode admission, JsonNode delivery, JsonNode durable, JsonNode completed) {
    final var projection = durable.path("persistence");
    final var reservation = durable.path("account");
    final var events = durable.path("events");
    assertEquals(1, admission.path("count").asInt());
    assertEquals(1, admission.path("outboxCount").asInt());
    assertEquals(order.account().toString(), admission.path("accountId").asText());
    assertEquals(order.route().partitionId(), admission.path("routingPartition").asInt());
    assertEquals(context.input().artifact().identity().contentSha256(),
        admission.path("artifactContentSha256").asText());
    assertEquals(1, delivery.path("count").asInt());
    assertEquals(admission.path("orderId"), delivery.path("orderId"));
    assertEquals(delivery.path("orderId"), completed.path("orderId"));
    assertEquals(delivery.path("execId"), completed.path("execId"));
    assertEquals("0", completed.path("terminalExecType").asText());
    assertEquals("RESTING", projection.path("status").asText());
    assertEquals(1, projection.path("projectionCount").asInt());
    assertEquals(0, projection.path("fillCount").asInt());
    assertEquals(order.rule().boardLotShares(), projection.path("leavesQuantityShares").asLong());
    assertEquals(delivery.path("eventId"), projection.path("lastEventId"));
    assertEquals(1, reservation.path("reservationCount").asInt());
    assertEquals(admission.path("reservationId"), reservation.path("reservationId"));
    assertEquals("RESERVATION_STATUS_ACCEPTED", reservation.path("status").asText());
    final var notional = BigDecimal.valueOf(order.instrument().referencePriceUnits(), 4)
        .multiply(BigDecimal.valueOf(order.rule().boardLotShares()));
    assertEquals(0, notional.compareTo(new BigDecimal(reservation.path("reservedNotional").asText())));
    assertEquals(0, notional.compareTo(new BigDecimal(reservation.path("limitReservedNotional").asText())));
    for (String owner : java.util.List.of("persistence", "account", "quickfix")) {
      assertEquals(1, events.path(owner + "Count").asInt());
    }
    assertFalse(events.path("persistencePayloadSha256").asText().isBlank());
    assertEquals(events.path("persistencePayloadSha256"), events.path("accountPayloadSha256"));
    assertEquals(events.path("persistencePayloadSha256"), events.path("quickfixPayloadSha256"));
    assertEquals(0, events.path("quarantineCount").asInt());
  }

  private static Order order(KubernetesBusinessEnvironment.PublicOrder context, UUID account) {
    final var artifact = context.input().artifact().artifact();
    final var instrument = artifact.marketSnapshot().instruments().stream()
        .filter(value -> "XTAI".equals(value.instrument().venueMic()))
        .filter(value -> value.eligibility() == InstrumentEligibility.ELIGIBLE)
        .filter(value -> value.referencePriceUnits() != null)
        .min(Comparator.comparing(ArtifactInstrument::instrument)).orElseThrow();
    final var rule = artifact.marketRules().rules().stream()
        .filter(value -> value.ruleId().equals(instrument.marketRuleId())).findFirst().orElseThrow();
    final var route = artifact.routingPolicy().assignments().stream()
        .filter(value -> value.instrument().equals(instrument.instrument())).findFirst().orElseThrow();
    return new Order(account, "P3-" + context.target().run().runId(), instrument, rule, route);
  }

  private static Map<String, String> clientEnvironment(Path fix, KubernetesFixCredentials credentials,
      Order order, int port) {
    final var values = new HashMap<>(credentials.clientEnvironment());
    values.put("SIMPLEMATCH_LIVE_FIX_HOST", "127.0.0.1");
    values.put("SIMPLEMATCH_LIVE_FIX_PORT", Integer.toString(port));
    values.put("SIMPLEMATCH_LIVE_FIX_ACCOUNT_ID", order.account().toString());
    values.put("SIMPLEMATCH_LIVE_FIX_CL_ORD_ID", order.clOrdId());
    values.put("SIMPLEMATCH_LIVE_FIX_SYMBOL", order.instrument().instrument().symbol());
    values.put("SIMPLEMATCH_LIVE_FIX_QUANTITY", Long.toString(order.rule().boardLotShares()));
    values.put("SIMPLEMATCH_LIVE_FIX_PRICE", BigDecimal.valueOf(
        order.instrument().referencePriceUnits(), 4).toPlainString());
    values.put("SIMPLEMATCH_RETAINED_FIX_TIMEOUT_SECONDS", "300");
    values.put("SIMPLEMATCH_RETAINED_FIX_STATE_DIR", fix.resolve("client-state").toString());
    values.put("SIMPLEMATCH_RETAINED_FIX_READY_FILE", fix.resolve("ready").toString());
    values.put("SIMPLEMATCH_RETAINED_FIX_RELEASE_FILE", fix.resolve("release").toString());
    values.put("SIMPLEMATCH_RETAINED_FIX_EVIDENCE", fix.resolve("receipt.json").toString());
    values.put("SIMPLEMATCH_RETAINED_FIX_COMPLETION_FILE", fix.resolve("complete").toString());
    values.put("SIMPLEMATCH_RETAINED_FIX_COMPLETION_EVIDENCE", fix.resolve("completed.json").toString());
    return Map.copyOf(values);
  }

  private JsonNode awaitSql(KubernetesPostgres postgres, String sql, Map<String, String> variables,
      Predicate<JsonNode> ready) throws Exception {
    final long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    do {
      final String observed = postgres.execute(sql, variables);
      if (!observed.isBlank()) {
        final var value = json.readTree(observed);
        if (ready.test(value)) {
          return value;
        }
      }
      Thread.sleep(500);
    } while (System.nanoTime() < deadline);
    throw new TimeoutException("public order durable effects did not converge within 90 seconds");
  }

  private static void awaitFile(Path file, Future<?> client) throws Exception {
    final long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (!Files.isRegularFile(file)) {
      if (client.isDone()) {
        client.get();
        throw new IllegalStateException("prepared FIX client stopped before " + file.getFileName());
      }
      if (System.nanoTime() >= deadline) {
        throw new TimeoutException("prepared FIX client did not produce " + file.getFileName());
      }
      Thread.sleep(100);
    }
  }

  private static String legacy(KubernetesBusinessEnvironment.PublicOrder context, String name)
      throws Exception {
    return Files.readString(context.settings().root()
        .resolve("scripts/end-to-end/critical-consumers/sql").resolve(name));
  }

  private static String resource(String name) throws Exception {
    try (var input = java.util.Objects.requireNonNull(
        RestingBuyKubernetesTest.class.getResourceAsStream(name), "missing scenario SQL")) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private record Order(UUID account, String clOrdId, ArtifactInstrument instrument,
      MarketRule rule, RoutingAssignment route) {}
}

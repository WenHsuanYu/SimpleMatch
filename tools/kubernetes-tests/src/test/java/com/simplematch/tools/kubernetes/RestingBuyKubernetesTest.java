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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies public FIX delivery, Matching recovery, and Market Data in one owned run. */
@Tag("kubernetes-live")
class RestingBuyKubernetesTest {
  private final ObjectMapper json = new ObjectMapper();
  @TempDir Path privateFiles;

  @Test
  void publicBuySurvivesOwnerRestartsAndDuplicateDelivery() throws Exception {
    final UUID account = UUID.randomUUID();
    final UUID seller = UUID.randomUUID();
    try (var credentials = KubernetesFixCredentials.create(privateFiles.resolve("fix"), account, seller)) {
      final var environment = new KubernetesBusinessEnvironment(credentials, account, seller);
      VerificationRun.verify(environment.request(), environment, run -> {
        try (credentials) {
          verify(environment.publicOrder(), credentials, account, seller);
          environment.verifyRuntime(run);
        }
      });
    }
  }

  private void verify(KubernetesBusinessEnvironment.PublicOrder context,
      KubernetesFixCredentials credentials, UUID account, UUID seller) throws Exception {
    final var target = context.target();
    final var postgres = new KubernetesPostgres(target);
    final var order = order(context, account);
    UUID sourceOrderId = null;
    final var variables = Map.of("account_id", account.toString(),
        "trading_day", context.settings().day().toString(), "cl_ord_id", order.clOrdId(),
        "now_ms", Long.toString(System.currentTimeMillis()), "exec_type", "0");
    postgres.execute(resource("/risk-command/account-buy.sql"), variables);
    Files.writeString(target.run().evidence().resolve("public-order.json"),
        json.writeValueAsString(order));
    final var events = new KubernetesMatchingEvents(context);
    context.admission().open(target, "gateway-initial");
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
        assertFixOrderIdentity(order, receipt);
        final var admission = postgres.await(legacy(context, "resting-buy-admission.sql"),
            variables, value -> "ACCEPTED".equals(value.path("state").asText()), Duration.ofSeconds(90));
        final var delivery = postgres.await(resource("/resting-buy/gateway-delivery.sql"),
            variables, value -> "SENT".equals(value.path("status").asText()), Duration.ofSeconds(90));
        final var durableVariables = new HashMap<>(variables);
        durableVariables.put("order_id", admission.path("orderId").asText());
        durableVariables.put("event_id", delivery.path("eventId").asText());
        final var durable = postgres.await(legacy(context, "resting-buy-durable-state.sql"),
            durableVariables, value -> value.path("events").path("accountCount").asInt() > 0
                && value.path("events").path("persistenceCount").asInt() > 0, Duration.ofSeconds(90));
        Files.writeString(fix.resolve("complete.next"), delivery.path("execId").asText());
        Files.move(fix.resolve("complete.next"), fix.resolve("complete"), StandardCopyOption.ATOMIC_MOVE);
        awaitFile(fix.resolve("completed.json"), finished);
        final var completed = json.readTree(fix.resolve("completed.json").toFile());
        final var baseline = new KubernetesOrderFacts(admission, delivery, durable, completed);
        sourceOrderId = UUID.fromString(admission.path("orderId").asText());
        Files.writeString(target.run().evidence().resolve("order-facts.json"), json.writeValueAsString(baseline));
        assertFacts(context, order, baseline, 1);
        long matchingInputOffset;
        try (var recovered = new KubernetesGatewayRecovery(context, fix, finished).replace(forward)) {
          final var afterAdmission = json.readTree(postgres.execute(
              legacy(context, "resting-buy-admission.sql"), variables));
          final var afterDurable = json.readTree(postgres.execute(
              legacy(context, "resting-buy-durable-state.sql"), durableVariables));
          final var afterDelivery = json.readTree(postgres.execute(
              resource("/resting-buy/gateway-delivery.sql"), variables));
          final var recoveredFacts = new KubernetesOrderFacts(
              afterAdmission, afterDelivery, afterDurable, completed);
          Files.writeString(target.run().evidence().resolve("gateway-recovery-facts.json"),
              json.writeValueAsString(recoveredFacts));
          assertEquals(baseline.admission(), afterAdmission, "retry must preserve the original admission");
          assertEquals(baseline.delivery(), afterDelivery, "retry must preserve singular delivery intent");
          assertEquals(baseline.durable(), afterDurable, "retry must not change durable state or revisions");
          matchingInputOffset = new KubernetesMatchingRecovery(context, fix, finished)
              .verify(events, admission, delivery, durableVariables);
          finished.get(60, TimeUnit.SECONDS);
        }
        new KubernetesMatchingCrash(context).verify(order.route().partitionId(), matchingInputOffset);
        new KubernetesConsumerRestart(context).verify();
      } finally {
        finished.cancel(true);
      }
    }
    final var business = new KubernetesQueryBusinessRead(context, account,
        java.util.Objects.requireNonNull(sourceOrderId),
        order.instrument().instrument().venueMic(), order.instrument().instrument().symbol());
    try (var marketData = new KubernetesMarketData(context)) {
      marketData.verify(business);
    }
    final var execution = new KubernetesQueryExecution(context, credentials, events, order, seller);
    execution.verify();
    final var active = new KubernetesQueryActiveProcessing(
        context, credentials, events, order, seller);
    active.verify();
    // The delayed BUY must not preempt the real R18 BUY/SELL cross on the same book.
    final var delayedAdmission = verifyDelayedOrder(context, credentials, order, events);
    new KubernetesTradingClose(context, events).verify(
        delayedAdmission, execution.utilizedNotional());
    business.settledBaseline();
    execution.settledBaseline();
    new KubernetesQueryReplay(context).verify(business, execution);
    final var policyFixture = new KubernetesQueryPolicyReplacement.Fixture(
        new KubernetesQueryPolicyReplacement.Accounts(account, seller),
        new KubernetesQueryPolicyReplacement.Orders(
            java.util.Objects.requireNonNull(sourceOrderId),
            UUID.fromString(execution.buyOrderId()),
            UUID.fromString(execution.sellOrderId()),
            UUID.fromString(active.cancelledOrderId())),
        new KubernetesQueryPolicyReplacement.MarketKey(
            order.instrument().instrument().venueMic(),
            order.instrument().instrument().symbol()));
    new KubernetesQueryPolicyReplacement(context).verify(policyFixture);
  }

  private void assertFacts(KubernetesBusinessEnvironment.PublicOrder context, Order order,
      KubernetesOrderFacts facts, int expectedOrders) {
    final var admission = facts.admission();
    final var delivery = facts.delivery();
    final var durable = facts.durable();
    final var completed = facts.completed();
    final var projection = durable.path("persistence");
    final var reservation = durable.path("account");
    final var events = durable.path("events");
    assertFixOrderIdentity(order, completed);
    assertEquals(1, admission.path("count").asInt());
    assertEquals(1, admission.path("outboxCount").asInt());
    assertEquals(order.account().toString(), admission.path("accountId").asText());
    assertEquals(order.route().partitionId(), admission.path("routingPartition").asInt());
    assertEquals(context.input().artifact().identity().contentSha256(),
        admission.path("artifactContentSha256").asText());
    assertEquals(1, delivery.path("count").asInt());
    assertEquals(admission.path("orderId"), delivery.path("orderId"));
    assertEquals(delivery.path("execId"), completed.path("execId"));
    assertEquals("0", completed.path("terminalExecType").asText());
    assertEquals("RESTING", projection.path("status").asText());
    assertEquals(expectedOrders, projection.path("projectionCount").asInt());
    assertEquals(0, projection.path("fillCount").asInt());
    assertEquals(order.rule().boardLotShares(), projection.path("leavesQuantityShares").asLong());
    assertEquals(delivery.path("eventId"), projection.path("lastEventId"));
    assertEquals(admission.path("orderId"), projection.path("orderId"));
    assertEquals(expectedOrders, reservation.path("reservationCount").asInt());
    assertEquals(admission.path("orderId"), reservation.path("orderId"));
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

  private static void assertFixOrderIdentity(Order order, JsonNode report) {
    // FIX tag 37 is Gateway's durable WAL identity; Risk uses a distinct UUID.
    assertEquals("O-" + order.clOrdId(), report.path("orderId").asText());
    assertEquals(order.clOrdId(), report.path("clOrdId").asText());
    assertEquals(order.account().toString(), report.path("accountId").asText());
  }

  private JsonNode verifyDelayedOrder(KubernetesBusinessEnvironment.PublicOrder context,
      KubernetesFixCredentials credentials, Order original, KubernetesMatchingEvents events)
      throws Exception {
    final var target = context.target();
    final var order = new Order(original.account(), "OUT-" + target.run().runId(),
        original.instrument(), original.rule(), original.route());
    final Path fix = Files.createDirectory(target.run().evidence().resolve("fix-outage"));
    final var variables = Map.of("account_id", order.account().toString(),
        "trading_day", context.settings().day().toString(), "cl_ord_id", order.clOrdId(),
        "exec_type", "0");
    try (var faults = new KubernetesCriticalDelivery(context, events, variables);
        var forward = target.client().forward("pod/" + target.gatewayPod(), 5001,
            fix.resolve("forward.log"));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var environment = new HashMap<>(clientEnvironment(fix, credentials, order, forward.port()));
      environment.put("SIMPLEMATCH_RETAINED_FIX_STATE_DIR",
          target.run().evidence().resolve("fix/client-state").toString());
      environment.put("SIMPLEMATCH_RETAINED_FIX_RECOVERY", "false");
      environment.put("SIMPLEMATCH_RETAINED_FIX_CANCEL", "false");
      environment.put("SIMPLEMATCH_RETAINED_FIX_REJECT_RELEASE", fix.resolve("reject-release").toString());
      environment.put("SIMPLEMATCH_RETAINED_FIX_REJECT_EVIDENCE", fix.resolve("rejected.json").toString());
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
        faults.prepare();
        context.admission().open(target, "outage-ready");
        Files.writeString(fix.resolve("release"), "release\n");
        awaitFile(fix.resolve("receipt.json"), finished);
        assertEquals("A", json.readTree(fix.resolve("receipt.json").toFile()).path("execType").asText());
        final var facts = faults.recover(finished, fix);
        // The original canceled order and R18 filled BUY are both durable before this order.
        assertFacts(context, order, facts, 3);
        finished.get(60, TimeUnit.SECONDS);
        return facts.admission();
      } finally {
        finished.cancel(true);
      }
    }
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

  static Map<String, String> clientEnvironment(Path fix, KubernetesFixCredentials credentials,
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
    values.put("SIMPLEMATCH_RETAINED_FIX_RECOVERY", "true");
    values.put("SIMPLEMATCH_RETAINED_FIX_RECOVERY_RELEASE", fix.resolve("recovery-release").toString());
    values.put("SIMPLEMATCH_RETAINED_FIX_RECOVERY_EVIDENCE", fix.resolve("recovery.json").toString());
    values.put("SIMPLEMATCH_RETAINED_FIX_CANCEL", "true");
    values.put("SIMPLEMATCH_RETAINED_FIX_CANCEL_RELEASE", fix.resolve("cancel-release").toString());
    values.put("SIMPLEMATCH_RETAINED_FIX_CANCEL_EVIDENCE", fix.resolve("cancel.json").toString());
    return Map.copyOf(values);
  }

  static void awaitFile(Path file, Future<?> client) throws Exception {
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

  record Order(UUID account, String clOrdId, ArtifactInstrument instrument,
      MarketRule rule, RoutingAssignment route) {}
}

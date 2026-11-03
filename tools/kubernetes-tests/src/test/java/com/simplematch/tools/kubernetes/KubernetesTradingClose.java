package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.tools.riskmatchinge2e.KafkaObservationSession.CloseBarrierExpectation;
import com.simplematch.tools.riskmatchinge2e.KafkaObservationSession.TopicEndPositions;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** Closes the existing session last and proves terminal Matching and consumer business effects. */
final class KubernetesTradingClose {
  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final KubernetesMatchingEvents events;
  private final ObjectMapper json = new ObjectMapper();
  private final com.fasterxml.jackson.databind.node.ObjectNode report = json.createObjectNode();
  private final long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();

  KubernetesTradingClose(KubernetesBusinessEnvironment.PublicOrder context,
      KubernetesMatchingEvents events) {
    this.context = context;
    this.events = events;
  }

  void verify(JsonNode admission) throws Exception {
    final var target = context.target();
    final var before = json.treeToValue(events.positions().required("matchingCommands"),
        TopicEndPositions.class);
    try (var forward = target.client().forward("pod/" + target.gatewayPod(), 8080,
        target.run().evidence().resolve("close-management-forward.log"))) {
      final var response = context.admission().closeDay(forward.port(), remaining());
      save("gateway", response);
      assertTrue(response.required("accepted").asBoolean());
      assertEquals("CLOSED", response.required("gateState").asText());
      assertEquals("MARKET_CLOSED", response.required("reason").asText());
    }
    final var after = awaitBarriers(before);
    final String session = target.client().read("get", "configmap", "matching-session-config",
        "-o", "json").required("data").required("trading_session_id").asText();
    save("barriers", events.closeBarriers(new CloseBarrierExpectation(session,
        context.settings().day().toString(), context.input().artifact().identity().contentSha256(),
        context.input().artifact().artifact().metadata().routingAlgorithmVersion(), before, after)));
    awaitClosedOwners(after);
    verifyExpiration(admission);
  }

  private TopicEndPositions awaitBarriers(TopicEndPositions before) throws Exception {
    do {
      remaining();
      final var after = json.treeToValue(events.positions().required("matchingCommands"),
          TopicEndPositions.class);
      boolean complete = true;
      for (int partition = 0; partition < before.partitions().size(); partition++) {
        final long delta = after.partitions().get(partition).offset()
            - before.partitions().get(partition).offset();
        assertTrue(delta >= 0 && delta <= 1, "close must publish one barrier per partition");
        complete &= delta == 1;
      }
      if (complete) {
        return after;
      }
      Thread.sleep(250);
    } while (true);
  }

  private void awaitClosedOwners(TopicEndPositions commands) throws Exception {
    final var target = context.target();
    final var pods = target.client().read("get", "pods", "-l",
        "app.kubernetes.io/name=matching," + OwnedNamespace.OWNER_LABEL + "="
            + target.run().runId(), "-o", "json").required("items");
    assertEquals(commands.partitions().size(), pods.size());
    final var runtimes = json.createArrayNode();
    for (var pod : pods) {
      final String owner = pod.required("metadata").required("name").asText() + ":"
          + pod.required("metadata").required("uid").asText();
      do {
        remaining();
        final var runtime = target.matchingRuntime(pod);
        final int partition = runtime.required("admission").required("partition_id").asInt();
        assertEquals(owner, runtime.required("admission").required("owner_id").asText());
        if ("CLOSED".equals(runtime.path("partition_state").asText())
            && runtime.required("pending_inputs").asInt() == 0
            && runtime.required("pending_publications").asInt() == 0
            && runtime.required("highest_contiguous_completed_offset").asLong()
                >= commands.partitions().get(partition).offset() - 1) {
          final long age = System.currentTimeMillis()
              - runtime.required("updated_at_epoch_ms").asLong();
          assertTrue(age >= 0 && age <= 5_000, "closed owner observation must be fresh");
          runtimes.add(runtime);
          break;
        }
        Thread.sleep(250);
      } while (true);
    }
    save("matching", runtimes);
  }

  private void verifyExpiration(JsonNode admission) throws Exception {
    final var postgres = new KubernetesPostgres(context.target());
    final var variables = new HashMap<String, String>();
    variables.put("account_id", admission.required("accountId").asText());
    variables.put("cl_ord_id", admission.required("clOrdId").asText());
    variables.put("trading_day", admission.required("tradingDay").asText());
    variables.put("order_id", admission.required("orderId").asText());
    variables.put("exec_type", "C");
    final String deliverySql;
    try (var resource = java.util.Objects.requireNonNull(getClass()
        .getResourceAsStream("/resting-buy/gateway-delivery.sql"))) {
      deliverySql = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    final var delivery = postgres.await(deliverySql, variables,
        value -> value.path("count").asInt() == 1, remaining());
    variables.put("event_id", delivery.required("eventId").asText());
    final String sql = Files.readString(context.settings().root()
        .resolve("scripts/end-to-end/critical-consumers/sql/resting-buy-durable-state.sql"));
    final var durable = postgres.await(sql, variables,
        value -> "EXPIRED".equals(value.path("persistence").path("status").asText())
            && List.of("account", "persistence", "quickfix").stream().allMatch(owner ->
                value.path("events").path(owner + "Count").asInt() == 1), remaining());
    save("delivery", delivery);
    save("durable", durable);
    assertEquals("RESERVATION_STATUS_RELEASED", durable.path("account").path("status").asText());
    for (String field : List.of("remainingQuantity", "reservedNotional", "limitReservedNotional",
        "limitUtilizedNotional")) {
      assertEquals(0, new BigDecimal(durable.required("account").required(field).asText()).signum());
    }
    assertEquals(0, durable.required("events").required("quarantineCount").asInt());
    assertEquals(delivery.required("eventId"), durable.required("persistence").required("lastEventId"));
  }

  private void save(String name, JsonNode value) throws Exception {
    report.set(name, value);
    Files.writeString(context.target().run().evidence().resolve("close-facts.json"),
        json.writeValueAsString(report));
  }

  private Duration remaining() throws TimeoutException {
    final long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new TimeoutException("terminal trading-session close exceeded three minutes");
    }
    return Duration.ofNanos(nanos);
  }
}

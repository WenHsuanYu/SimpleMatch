package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.quickfixgateway.fix.PreparedFixSubmission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Releases an actual public FIX IOC while Query has no Pod or surviving old CRI task. */
final class KubernetesQueryActiveProcessing {
  private static final Duration IOC_BUDGET = Duration.ofMinutes(2);
  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final KubernetesFixCredentials credentials;
  private final KubernetesMatchingEvents events;
  private final RestingBuyKubernetesTest.Order selected;
  private final UUID account;
  private final KubernetesPostgres postgres;
  private final ObjectMapper json = new ObjectMapper();
  private final Path evidence;
  private String cancelledOrderId;

  KubernetesQueryActiveProcessing(KubernetesBusinessEnvironment.PublicOrder context,
      KubernetesFixCredentials credentials, KubernetesMatchingEvents events,
      RestingBuyKubernetesTest.Order selected, UUID account) throws Exception {
    this.context = context;
    this.credentials = credentials;
    this.events = events;
    this.selected = selected;
    this.account = account;
    postgres = new KubernetesPostgres(context.target());
    evidence = Files.createDirectory(context.target().run().evidence().resolve("query-active"));
  }

  void verify() throws Exception {
    final var target = context.target();
    final String orderKey = "R18-IOC-" + target.run().runId();
    final var order = new RestingBuyKubernetesTest.Order(account, orderKey,
        selected.instrument(), selected.rule(), selected.route());
    final var variables = Map.of("account_id", account.toString(),
        "cl_ord_id", orderKey, "trading_day", context.settings().day().toString());
    final var deployment = ownedDeployment();
    final String uid = deployment.path("metadata").path("uid").asText();
    assertFalse(uid.isBlank());
    assertEquals(2, deployment.path("spec").path("replicas").asInt(-1));
    assertEquals(2, deployment.path("status").path("readyReplicas").asInt(-1));
    final var fence = new KubernetesQueryStopFence(context);
    final var prior = fence.capture();
    final var marketDataPods = marketDataSurvivors();
    final var path = Files.createDirectory(evidence.resolve("fix"));
    try (var forward = target.client().forward("pod/" + target.gatewayPod(), 5001,
            path.resolve("forward.log"));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var environment = new HashMap<>(
          RestingBuyKubernetesTest.clientEnvironment(path, credentials, order, forward.port()));
      environment.put("SIMPLEMATCH_LIVE_FIX_SIDE", "1");
      environment.put("SIMPLEMATCH_LIVE_FIX_TIME_IN_FORCE", "3");
      environment.put("SIMPLEMATCH_RETAINED_FIX_STATE_DIR", target.run().evidence()
          .resolve("fix/client-state").toString());
      environment.put("SIMPLEMATCH_RETAINED_FIX_RECOVERY", "false");
      environment.put("SIMPLEMATCH_RETAINED_FIX_CANCEL", "false");
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
        RestingBuyKubernetesTest.awaitFile(path.resolve("ready"), finished);
        context.admission().open(target, "r18-ioc-ready");
        final var before = events.positions();
        Files.write(evidence.resolve("source-before.json"), json.writeValueAsBytes(before));
        requireDeployment(uid);
        boolean scaled = true;
        try {
          target.client().run("", "scale", "deployment/query-service", "--replicas=0");
          fence.awaitStopped(prior, Duration.ofSeconds(20));
          assertEquals(marketDataPods, marketDataSurvivors(),
              "Market Data workloads stopped when Query reached zero Pods");
          Files.write(evidence.resolve("old-query-tasks.json"), json.writeValueAsBytes(prior));
          assertZero(uid, fence, prior);
          assertEquals(marketDataPods, marketDataSurvivors(),
              "Market Data projection or streamer lost its original live Pod during Query outage");
          // Refresh Gateway's business safety observation while the client remains at its
          // public FIX release barrier; Query is deliberately still absent.
          context.admission().open(target, "r18-ioc-zero-query");
          Files.writeString(path.resolve("release"), "release\n");
          RestingBuyKubernetesTest.awaitFile(path.resolve("receipt.json"), finished);
          final var receipt = json.readTree(path.resolve("receipt.json").toFile());
          assertEquals(account.toString(), receipt.path("accountId").asText());
          final var admission = postgres.await(Files.readString(context.settings().root().resolve(
              "scripts/end-to-end/critical-consumers/sql/resting-buy-admission.sql")),
              variables, data -> "ACCEPTED".equals(data.path("state").asText()), IOC_BUDGET);
          cancelledOrderId = admission.path("orderId").asText();
          assertEquals(1, admission.path("outboxCount").asInt(-1));
          final int partition = admission.path("routingPartition").asInt(-1);
          assertTrue(partition >= 0 && partition < 15);
          final long start = before.path("matchingEvents").path("partitions")
              .get(partition).path("offset").asLong(-1);
          assertTrue(start >= 0, "real matching event lower bound was not captured");
          final var observed = events.observeAccepted(admission, start, "query-ioc");
          assertEquals(admission.path("orderId"), observed.path("orderId"));
          assertEquals(admission.path("commandId"), observed.path("sourceCommandId"));
          final var finalOrder = postgres.await("""
              SELECT json_build_object('orderId', p.order_id, 'status', p.status,
                'filled', p.cumulative_quantity_shares, 'leaves', p.leaves_quantity_shares,
                'reserved', r.reserved_notional, 'remaining', r.remaining_quantity,
                'reservation', r.status, 'lastEventId', encode(p.last_event_id, 'hex'),
                'persistenceCount', (SELECT count(*) FROM persistence.matching_event_inbox
                  WHERE event_id = p.last_event_id),
                'accountCount', (SELECT count(*) FROM account_service.matching_event_inbox
                  WHERE event_id = p.last_event_id),
                'fixCount', (SELECT count(*) FROM quickfix_gateway.matching_event_inbox
                  WHERE event_id = p.last_event_id))::text
              FROM persistence.matching_order_projections p
              JOIN account_service.account_reservations r ON r.order_id = p.order_id
              WHERE p.order_id = :'order_id'::uuid;
              """, Map.of("order_id", admission.path("orderId").asText()),
              data -> "CANCELLED".equals(data.path("status").asText())
                  && "RESERVATION_STATUS_RELEASED".equals(data.path("reservation").asText())
                  && data.path("persistenceCount").asInt() == 1
                  && data.path("accountCount").asInt() == 1
                  && data.path("fixCount").asInt() == 1, IOC_BUDGET);
          assertEquals(0, finalOrder.path("filled").asLong(-1));
          // An IOC terminal cancellation records the unfilled quantity as terminal
          // leaves; it is not a live resting quantity after reservation release.
          assertEquals(selected.rule().boardLotShares(),
              finalOrder.path("leaves").asLong(-1));
          assertEquals(0, finalOrder.path("remaining").asLong(-1));
          assertEquals(0, new java.math.BigDecimal(
              finalOrder.path("reserved").asText()).signum());
          final var delivery = postgres.await("""
              SELECT json_build_object('execId', exec_id, 'status', status,
                'eventId', encode(event_id, 'hex'),
                'sourcePartition', source_partition, 'sourceOffset', source_offset)::text
              FROM quickfix_gateway.fix_delivery_intents
              WHERE recipient_order_id = :'order_id'::uuid AND status = 'SENT'
              ORDER BY source_offset DESC LIMIT 1;
              """, Map.of("order_id", admission.path("orderId").asText()),
              data -> "SENT".equals(data.path("status").asText()), IOC_BUDGET);
          Files.writeString(path.resolve("complete.next"), delivery.path("execId").asText());
          Files.move(path.resolve("complete.next"), path.resolve("complete"),
              StandardCopyOption.ATOMIC_MOVE);
          RestingBuyKubernetesTest.awaitFile(path.resolve("completed.json"), finished);
          finished.get(60, TimeUnit.SECONDS);
          final var after = events.positions();
          assertTrue(after.path("matchingEvents").path("partitions")
              .get(partition).path("offset").asLong(-1) > start);
          final var quarantine = postgres.execute("""
              SELECT (SELECT count(*) FROM persistence.matching_consumer_quarantines
                WHERE status = 'QUARANTINED') +
                (SELECT count(*) FROM account_service.matching_event_consumer_quarantines
                WHERE status = 'QUARANTINED') +
                (SELECT count(*) FROM quickfix_gateway.matching_consumer_quarantines
                WHERE status = 'QUARANTINED');
              """, Map.of());
          assertEquals("0", quarantine.strip(), "IOC must not quarantine critical deliveries");
          assertZero(uid, fence, prior);
          Files.write(evidence.resolve("zero-pod-ioc.json"), json.writeValueAsBytes(Map.of(
              "admission", admission, "matchingEvent", observed,
              "persistenceAccount", finalOrder, "fixDelivery", delivery,
              "sourceBefore", before, "sourceAfter", after,
              "queryPodCount", 0, "oldQueryTasksStopped", true)));
        } finally {
          if (scaled) {
            requireDeployment(uid);
            target.client().run("", "scale", "deployment/query-service", "--replicas=2");
            target.client().execute("", Duration.ofMinutes(3), "rollout", "status",
                "deployment/query-service", "--timeout=180s").checked();
            final var restored = requireDeployment(uid);
            assertEquals(2, restored.path("status").path("readyReplicas").asInt(-1));
            context.workloadImages().verifySelected(target.client(), target.run(),
                "query-active-restored", "query-service", 2);
          }
        }
      } finally {
        finished.cancel(true);
      }
    }
  }

  String cancelledOrderId() {
    if (cancelledOrderId == null || !Files.exists(evidence.resolve("zero-pod-ioc.json"))) {
      throw new IllegalStateException("real Y IOC cancellation was not verified");
    }
    return cancelledOrderId;
  }

  private JsonNode ownedDeployment() throws Exception {
    final var value = context.target().client().read(
        "get", "deployment", "query-service", "-o", "json");
    assertEquals(context.target().run().runId(), value.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    assertEquals(context.target().replacements().get("simplematch/query-service:local"),
        value.at("/spec/template/spec/containers/0/image").asText());
    return value;
  }

  /** Retains a distinct positive liveness check for independent Market Data workloads. */
  private Map<String, String> marketDataSurvivors() throws Exception {
    final var result = new HashMap<String, String>();
    for (String workload : java.util.List.of("market-data-projection", "marketdata-streamer")) {
      final var deployment = context.target().client().read(
          "get", "deployment", workload, "-o", "json");
      assertEquals(context.target().run().runId(), deployment.path("metadata")
          .path("labels").path(OwnedNamespace.OWNER_LABEL).asText());
      final int desired = deployment.path("spec").path("replicas").asInt(-1);
      assertTrue(desired > 0 && deployment.path("status").path("readyReplicas").asInt(-1) == desired,
          "Market Data workload lost Ready replicas during Query outage");
      final var pods = context.target().client().read("get", "pods", "-l",
          "app.kubernetes.io/name=" + workload + ","
              + OwnedNamespace.OWNER_LABEL + "=" + context.target().run().runId(),
          "-o", "json").required("items");
      assertEquals(desired, pods.size(), "Market Data Pod count changed during Query outage");
      for (var pod : pods) {
        assertEquals("Running", pod.path("status").path("phase").asText());
        assertTrue(pod.at("/status/containerStatuses/0/ready").asBoolean(false),
            "Market Data Pod was not serving");
        final String uid = pod.path("metadata").path("uid").asText();
        assertFalse(uid.isBlank());
        result.put(workload + ":" + uid, uid);
      }
    }
    return Map.copyOf(result);
  }

  private JsonNode requireDeployment(String uid) throws Exception {
    final var value = ownedDeployment();
    assertEquals(uid, value.path("metadata").path("uid").asText(),
        "Query Deployment owner changed during the active fault");
    return value;
  }

  private void assertZero(String uid, KubernetesQueryStopFence fence,
      java.util.List<KubernetesQueryStopFence.Task> prior) throws Exception {
    assertEquals(0, requireDeployment(uid).path("spec").path("replicas").asInt(-1));
    fence.awaitStopped(prior, Duration.ofSeconds(20));
  }
}

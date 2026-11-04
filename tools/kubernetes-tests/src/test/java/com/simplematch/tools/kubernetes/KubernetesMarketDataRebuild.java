package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplematch.tools.recovery.MarketDataEvidenceVerifier;
import com.simplematch.tools.riskmatchinge2e.MarketDataSnapshotObservationMain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Retains real projection replay, a Redis outage, durable pending repair and public snapshots. */
final class KubernetesMarketDataRebuild {
  private static final Duration OBSERVATION_BUDGET = Duration.ofMinutes(3);
  private static final String CONSUMER_STATE = """
      SELECT json_build_object(
        'persistenceProgress', COALESCE((SELECT json_agg(row_to_json(p) ORDER BY p.partition_id)
          FROM (SELECT partition_id, last_processed_offset
            FROM persistence.matching_consumer_progress
            WHERE consumer_name = 'persistence-matching-events') p), '[]'::json),
        'accountProgress', COALESCE((SELECT json_agg(row_to_json(a) ORDER BY a.partition_id)
          FROM (SELECT partition_id, last_processed_offset
            FROM account_service.matching_event_consumer_progress
            WHERE consumer_name = 'account-final-matching-events') a), '[]'::json),
        'quickfixProgress', COALESCE((SELECT json_agg(row_to_json(q) ORDER BY q.partition_id)
          FROM (SELECT partition_id, last_processed_offset
            FROM quickfix_gateway.matching_consumer_progress
            WHERE consumer_name = 'quickfix-final-matching-events') q), '[]'::json),
        'persistenceQuarantines', (SELECT COUNT(*) FROM persistence.matching_consumer_quarantines
          WHERE status = 'QUARANTINED'),
        'accountQuarantines', (SELECT COUNT(*) FROM account_service.matching_event_consumer_quarantines
          WHERE status = 'QUARANTINED'),
        'quickfixQuarantines', (SELECT COUNT(*) FROM quickfix_gateway.matching_consumer_quarantines
          WHERE status = 'QUARANTINED'),
        'persistenceQuarantineHistory',
          (SELECT COUNT(*) FROM persistence.matching_consumer_quarantines),
        'accountQuarantineHistory',
          (SELECT COUNT(*) FROM account_service.matching_event_consumer_quarantines),
        'quickfixQuarantineHistory',
          (SELECT COUNT(*) FROM quickfix_gateway.matching_consumer_quarantines),
        'quickfixPendingIntents', (SELECT COUNT(*) FROM quickfix_gateway.fix_delivery_intents
          WHERE status = 'PENDING'),
        'admissionStateCounts', COALESCE((SELECT json_agg(row_to_json(a) ORDER BY a.state)
          FROM (SELECT state, COUNT(*) AS count FROM risk_service.admission_journal
            GROUP BY state) a), '[]'::json),
        'accountReservationStateCounts', COALESCE((SELECT json_agg(row_to_json(r)
          ORDER BY r.status) FROM (SELECT status, COUNT(*) AS count
            FROM account_service.account_reservations GROUP BY status) r), '[]'::json),
        'marketDataProgress', COALESCE((SELECT json_agg(row_to_json(m) ORDER BY m.partition_id)
          FROM (SELECT partition_id, last_processed_offset, recovery_state
            FROM market_data_projection.partition_projection_progress) m), '[]'::json),
        'marketDataInstrumentCount',
          (SELECT COUNT(*) FROM market_data_projection.instrument_market_data),
        'marketDataDeadLetters',
          (SELECT COUNT(*) FROM market_data_projection.matching_event_dead_letters),
        'activeMatchingOrders', (SELECT COUNT(*) FROM persistence.matching_order_projections
          WHERE status IN ('RESTING', 'PARTIALLY_FILLED'))
      )::text;
      """;
  private static final String PROJECTION_STATE = """
      SELECT json_build_object(
        'instrumentCount', (SELECT COUNT(*) FROM market_data_projection.instrument_market_data),
        'pendingRedisCount', (SELECT COUNT(*) FROM market_data_projection.instrument_market_data
          WHERE redis_snapshot_pending = TRUE),
        'deadLetterCount', (SELECT COUNT(*) FROM market_data_projection.matching_event_dead_letters)
      )::text;
      """;
  private static final String PENDING_REPAIR = """
      SELECT json_build_object('pendingRedisCount',
        (SELECT COUNT(*) FROM market_data_projection.instrument_market_data
          WHERE redis_snapshot_pending = TRUE))::text;
      """;

  private final KubernetesMarketData market;

  KubernetesMarketDataRebuild(KubernetesMarketData market) {
    this.market = market;
  }

  void verify(KubernetesMarketData.Instrument instrument) throws Exception {
    replayAndRestore(instrument);
    verifyRestoration();
  }

  private void replayAndRestore(KubernetesMarketData.Instrument instrument) throws Exception {
    market.save("critical-before.json", market.query(CONSUMER_STATE));
    try (var streamer = market.client().forward("service/marketdata-streamer", 50053,
        market.evidence().resolve("r16-streamer-forward.log"))) {
      observe("baseline", streamer.port(), instrument, true);
      market.resetProjection("rebuilt");
      market.stopRedis();
      observe("rebuilt", streamer.port(), instrument, false);
    }
    final var outage = market.query(PROJECTION_STATE);
    market.save("outage-projection-state.json", outage);
    assertTrue(outage.path("instrumentCount").asInt() > 0);
    assertTrue(outage.path("pendingRedisCount").asInt() > 0,
        "real Redis outage must leave durable repair work");
    assertEquals(0, outage.path("deadLetterCount").asInt(-1));
    market.restoreRedis();
    final var budget = OperationBudget.start(OBSERVATION_BUDGET);
    JsonNode repair = market.query(PENDING_REPAIR);
    while (repair.path("pendingRedisCount").asInt(-1) != 0) {
      Thread.sleep(budget.remaining(Duration.ofMillis(500)));
      repair = market.query(PENDING_REPAIR);
      budget.remaining();
    }
    final String key = "marketdata:snapshot:" + instrument.venueMic() + ":" + instrument.symbol();
    final String cache = market.client().run("", "exec", "deployment/redis", "--",
        "redis-cli", "EXISTS", key).trim();
    assertEquals("1", cache, "Redis must contain the restored public snapshot");
    market.restoreProjection();
  }

  private void verifyRestoration() throws Exception {
    final var consumers = market.query(CONSUMER_STATE);
    market.save("critical-after.json", consumers);
    final boolean consumerHealthy = java.util.List.of("persistenceQuarantines",
        "accountQuarantines", "quickfixQuarantines").stream()
        .allMatch(field -> consumers.path(field).asInt(-1) == 0);
    market.ready("marketdata-streamer", 1);
    final var matching = market.client().read("get", "pods", "-l", "app.kubernetes.io/name=matching,"
        + OwnedNamespace.OWNER_LABEL + "=" + market.target().run().runId(), "-o", "json")
        .required("items");
    int matchingReady = 0;
    for (var pod : matching) {
      if (java.util.stream.StreamSupport.stream(pod.path("status").path("conditions")
          .spliterator(), false).anyMatch(condition -> "Ready".equals(condition.path("type").asText())
              && "True".equals(condition.path("status").asText()))) {
        matchingReady++;
      }
    }
    assertEquals(15, matching.size(), "Matching fleet must retain all fifteen run-owned Pods");
    market.save("restoration.json", java.util.Map.of(
        "redisRepaired", true, "projectionReady", true, "streamerReady", true,
        "matchingReady", matchingReady, "criticalConsumersReady", consumerHealthy));
    new MarketDataEvidenceVerifier().write(market.evidence(),
        market.evidence().resolve("r16-verdict.json"));
  }

  private void observe(String phase, int port, KubernetesMarketData.Instrument instrument,
      boolean reset) throws Exception {
    final Path output = market.evidence().resolve(phase + "-snapshot.json");
    final Path signal = market.evidence().resolve(phase + "-observer-ready");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final Future<?> observed = executor.submit(() -> {
        MarketDataSnapshotObservationMain.main(new String[] {
            "--host", "127.0.0.1", "--port", Integer.toString(port),
            "--venue-mic", instrument.venueMic(), "--symbol", instrument.symbol(),
            "--timeout-seconds", "180", "--evidence", output.toString(),
            "--ready-file", signal.toString()
        });
        return null;
      });
      try {
        awaitReady(signal, observed);
        if (reset) {
          market.resetProjection(phase);
        }
        market.restartProjectionFromEarliest(phase);
        observed.get(OBSERVATION_BUDGET.toSeconds(), TimeUnit.SECONDS);
        final var snapshot = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toFile());
        assertTrue(snapshot.path("completeSnapshot").asBoolean(),
            "public gRPC must deliver a complete snapshot");
      } finally {
        observed.cancel(true);
      }
    }
  }

  private static void awaitReady(Path signal, Future<?> observed) throws Exception {
    final var budget = OperationBudget.start(Duration.ofSeconds(30));
    while (true) {
      budget.remaining();
      if (Files.isRegularFile(signal) && Files.size(signal) > 0) {
        return;
      }
      if (observed.isDone()) {
        observed.get();
        throw new IllegalStateException("public Market Data observer ended before subscribing");
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(200)));
    }
  }
}

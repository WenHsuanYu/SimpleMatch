package com.simplematch.riskservice.cdc;

import static com.simplematch.config.delivery.DeliveryMetric.CONNECTOR_LAG_EVENTS;
import static com.simplematch.config.delivery.DeliveryMetric.OUTBOX_AGE_MILLIS;
import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.delivery.InMemoryDeliveryMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class CdcDeliveryMonitorTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-02T00:00:05Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("refreshes durable lag and telemetry after the observer group catches up")
  void refreshesDurableLagAndTelemetryAfterTheObserverGroupCatchesUp() {
    final RecordingProgressStore store = new RecordingProgressStore();
    final InMemoryDeliveryMetrics metrics = new InMemoryDeliveryMetrics();
    final CdcDeliveryMonitor monitor =
        new CdcDeliveryMonitor(
            store,
            topic -> true,
            metrics,
            "matching.commands",
            "matching.commands",
            CLOCK,
            new TransactionTemplate(new NoOpTransactionManager()));

    monitor.refresh();

    assertThat(store.refreshes).containsExactly("matching.commands|matching.commands|1788307205000");
    assertThat(metrics.observation(CONNECTOR_LAG_EVENTS, "risk-cdc-delivery"))
        .isEqualTo(7L);
    assertThat(metrics.observation(OUTBOX_AGE_MILLIS, "risk-cdc-delivery"))
        .isEqualTo(1_250L);
  }

  @Test
  @DisplayName("leaves the previous metric to age out while Kafka progress is behind")
  void leavesThePreviousMetricToAgeOutWhileKafkaProgressIsBehind() {
    final RecordingProgressStore store = new RecordingProgressStore();
    final InMemoryDeliveryMetrics metrics = new InMemoryDeliveryMetrics();
    final CdcDeliveryMonitor monitor =
        new CdcDeliveryMonitor(
            store,
            topic -> false,
            metrics,
            "matching.commands",
            "matching.commands",
            CLOCK,
            new TransactionTemplate(new NoOpTransactionManager()));

    monitor.refresh();

    assertThat(store.refreshes).isEmpty();
    assertThat(metrics.observation(CONNECTOR_LAG_EVENTS, "risk-cdc-delivery")).isNull();
  }

  private static final class RecordingProgressStore implements CdcDeliveryProgressStore {
    private final List<String> refreshes = new ArrayList<>();

    @Override
    public void observe(CdcDeliveryObservation observation) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public CdcDeliverySnapshot refresh(String metricName, String topic, long measuredAtUnixMs) {
      refreshes.add(metricName + "|" + topic + "|" + measuredAtUnixMs);
      return new CdcDeliverySnapshot(7, 1_250);
    }
  }

  private static final class NoOpTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {}

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {}
  }
}

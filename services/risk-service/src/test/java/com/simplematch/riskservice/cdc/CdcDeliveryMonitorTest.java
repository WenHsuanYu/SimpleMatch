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
import org.junit.jupiter.api.Test;

class CdcDeliveryMonitorTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-02T00:00:05Z"), ZoneOffset.UTC);

  @Test
  void refreshesDurableLagAndTelemetryAfterTheObserverGroupCatchesUp() {
    final RecordingProgressStore store = new RecordingProgressStore();
    final InMemoryDeliveryMetrics metrics = new InMemoryDeliveryMetrics();
    final CdcDeliveryMonitor monitor =
        new CdcDeliveryMonitor(
            store, topic -> true, metrics, "matching.commands", "matching.commands", CLOCK);

    monitor.refresh();

    assertThat(store.refreshes).containsExactly("matching.commands|matching.commands|1788307205000");
    assertThat(metrics.observation(CONNECTOR_LAG_EVENTS, "risk-cdc-delivery"))
        .isEqualTo(7L);
    assertThat(metrics.observation(OUTBOX_AGE_MILLIS, "risk-cdc-delivery"))
        .isEqualTo(1_250L);
  }

  @Test
  void leavesThePreviousMetricToAgeOutWhileKafkaProgressIsBehind() {
    final RecordingProgressStore store = new RecordingProgressStore();
    final InMemoryDeliveryMetrics metrics = new InMemoryDeliveryMetrics();
    final CdcDeliveryMonitor monitor =
        new CdcDeliveryMonitor(
            store, topic -> false, metrics, "matching.commands", "matching.commands", CLOCK);

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
}

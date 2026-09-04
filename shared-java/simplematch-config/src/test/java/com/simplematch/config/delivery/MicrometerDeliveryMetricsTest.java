package com.simplematch.config.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerDeliveryMetricsTest {
  @Test
  void exposesStableOutcomeLabelsAndOperationalObservations() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final MicrometerDeliveryMetrics metrics = new MicrometerDeliveryMetrics(registry);
    final DeliveryPosition position = new DeliveryPosition("matching.events", 2, 17);

    metrics.increment(DeliveryMetric.RETRY, "quickfix-execution-projection", position);
    metrics.observe(DeliveryMetric.CONSUMER_LAG_EVENTS, "quickfix-execution-projection", 4);

    assertThat(
            registry
                .get("simplematch.delivery.events")
                .tag("metric", "retry")
                .tag("component", "quickfix-execution-projection")
                .tag("topic", "matching.events")
                .tag("partition", "2")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .get("simplematch.delivery.observations")
                .tag("metric", "consumer_lag_events")
                .tag("component", "quickfix-execution-projection")
                .gauge()
                .value())
        .isEqualTo(4.0);
  }
}

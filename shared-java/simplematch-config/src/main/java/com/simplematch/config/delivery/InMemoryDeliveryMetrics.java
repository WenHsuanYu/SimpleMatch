package com.simplematch.config.delivery;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Small deterministic metrics sink used by local checks and adapter tests. */
public final class InMemoryDeliveryMetrics implements DeliveryMetrics {
  private final Map<CounterKey, Long> counters = new HashMap<>();
  private final Map<ObservationKey, Long> observations = new HashMap<>();

  @Override
  public synchronized void increment(
      DeliveryMetric metric, String component, DeliveryPosition position) {
    counters.merge(
        new CounterKey(metric, component, position.topic(), position.partition()), 1L, Long::sum);
  }

  @Override
  public synchronized void observe(DeliveryMetric metric, String component, long value) {
    observations.put(new ObservationKey(metric, component), value);
  }

  /** Returns the counter value for one component and partition. */
  public synchronized long count(
      DeliveryMetric metric, String component, DeliveryPosition position) {
    return counters.getOrDefault(
        new CounterKey(metric, component, position.topic(), position.partition()), 0L);
  }

  /** Returns the latest observation for one component, or {@code null} when absent. */
  public synchronized Long observation(DeliveryMetric metric, String component) {
    return observations.get(new ObservationKey(metric, component));
  }

  private record CounterKey(DeliveryMetric metric, String component, String topic, int partition) {
    private CounterKey {
      Objects.requireNonNull(metric, "metric");
      Objects.requireNonNull(component, "component");
      Objects.requireNonNull(topic, "topic");
    }
  }

  private record ObservationKey(DeliveryMetric metric, String component) {
    private ObservationKey {
      Objects.requireNonNull(metric, "metric");
      Objects.requireNonNull(component, "component");
    }
  }
}

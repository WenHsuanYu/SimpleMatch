package com.simplematch.config.delivery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Exports delivery-policy counters and observations through the service MeterRegistry. */
public final class MicrometerDeliveryMetrics implements DeliveryMetrics {
  private final MeterRegistry registry;
  private final ConcurrentHashMap<ObservationKey, AtomicLong> observations =
      new ConcurrentHashMap<>();

  /** Creates the registry-backed delivery metrics adapter. */
  public MicrometerDeliveryMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "meter registry");
  }

  @Override
  public void increment(DeliveryMetric metric, String component, DeliveryPosition position) {
    Objects.requireNonNull(metric, "delivery metric");
    Objects.requireNonNull(component, "delivery component");
    Objects.requireNonNull(position, "delivery position");
    final Counter counter =
        Counter.builder("simplematch.delivery.events")
            .description("Delivery policy outcomes")
            .tags(
                Tags.of(
                    "metric", metric.name().toLowerCase(java.util.Locale.ROOT),
                    "component", component,
                    "topic", position.topic(),
                    "partition", Integer.toString(position.partition())))
            .register(registry);
    counter.increment();
  }

  @Override
  public void observe(DeliveryMetric metric, String component, long value) {
    Objects.requireNonNull(metric, "delivery metric");
    Objects.requireNonNull(component, "delivery component");
    final ObservationKey key = new ObservationKey(metric, component);
    final AtomicLong observation =
        observations.computeIfAbsent(
            key,
            ignored -> {
              final AtomicLong valueHolder = new AtomicLong();
              registry.gauge(
                  "simplematch.delivery.observations",
                  Tags.of(
                      "metric", metric.name().toLowerCase(java.util.Locale.ROOT),
                      "component", component),
                  valueHolder);
              return valueHolder;
            });
    observation.set(value);
  }

  private record ObservationKey(DeliveryMetric metric, String component) {
    private ObservationKey {
      Objects.requireNonNull(metric, "delivery metric");
      Objects.requireNonNull(component, "delivery component");
    }
  }
}

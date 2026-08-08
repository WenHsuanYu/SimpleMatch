package com.simplematch.config.delivery;

/** Metrics port for delivery policies and deployment-specific telemetry exporters. */
public interface DeliveryMetrics {
  /** Increments a position-labelled counter. */
  void increment(DeliveryMetric metric, String component, DeliveryPosition position);

  /** Records the latest numeric observation for a component. */
  void observe(DeliveryMetric metric, String component, long value);

  /** Returns a no-op implementation for services without an exporter binding. */
  static DeliveryMetrics noop() {
    return new DeliveryMetrics() {
      @Override
      public void increment(DeliveryMetric metric, String component, DeliveryPosition position) {
        // Deliberately empty: the application remains correct when telemetry is unavailable.
      }

      @Override
      public void observe(DeliveryMetric metric, String component, long value) {
        // Deliberately empty: the application remains correct when telemetry is unavailable.
      }
    };
  }
}

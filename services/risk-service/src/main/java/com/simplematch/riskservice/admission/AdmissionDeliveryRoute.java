package com.simplematch.riskservice.admission;

/**
 * Persisted delivery route associated with one admission journal entry.
 *
 * @param routingPartition explicit Kafka partition, or {@code null} when the producer assigns it
 */
public record AdmissionDeliveryRoute(Integer routingPartition) {
  /** Validates an optional non-negative partition assignment. */
  public AdmissionDeliveryRoute {
    if (routingPartition != null && routingPartition < 0) {
      throw new IllegalArgumentException("routing_partition must be >= 0");
    }
  }

  /** Returns a route without an explicit partition assignment. */
  public static AdmissionDeliveryRoute unassigned() {
    return new AdmissionDeliveryRoute(null);
  }

  /** Returns a route with an explicit partition assignment. */
  public static AdmissionDeliveryRoute assigned(int routingPartition) {
    return new AdmissionDeliveryRoute(routingPartition);
  }
}

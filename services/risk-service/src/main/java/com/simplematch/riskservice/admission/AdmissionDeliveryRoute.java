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

  /**
   * Returns the persisted partition required for an accepted delivery.
   *
   * @return assigned non-negative Kafka partition
   * @throws IllegalStateException when the route has no persisted partition
   */
  public int requireAssignedPartition() {
    if (routingPartition == null) {
      throw new IllegalStateException("accepted admission requires a persisted routing partition");
    }
    return routingPartition;
  }
}

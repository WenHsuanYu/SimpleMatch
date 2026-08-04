package com.simplematch.riskservice.admission;

import java.util.Objects;
import java.util.UUID;

/**
 * Persisted delivery route associated with one admission journal entry.
 *
 * @param routingPartition explicit Kafka partition, or {@code null} when the producer assigns it
 * @param routingPolicyId authoritative policy identity, or {@code null} for a legacy row
 */
public record AdmissionDeliveryRoute(Integer routingPartition, UUID routingPolicyId) {
  /** Validates an optional non-negative partition assignment. */
  public AdmissionDeliveryRoute {
    if (routingPartition != null && routingPartition < 0) {
      throw new IllegalArgumentException("routing_partition must be >= 0");
    }
  }

  /** Preserves the one-field constructor used by legacy journal fixtures. */
  public AdmissionDeliveryRoute(Integer routingPartition) {
    this(routingPartition, null);
  }

  /** Returns a route without an explicit partition assignment. */
  public static AdmissionDeliveryRoute unassigned() {
    return new AdmissionDeliveryRoute(null, null);
  }

  /** Returns a route with an explicit partition assignment. */
  public static AdmissionDeliveryRoute assigned(int routingPartition) {
    return new AdmissionDeliveryRoute(routingPartition, null);
  }

  /** Returns a route selected by an authoritative policy. */
  public static AdmissionDeliveryRoute assigned(UUID routingPolicyId, int routingPartition) {
    return new AdmissionDeliveryRoute(
        routingPartition, Objects.requireNonNull(routingPolicyId, "routingPolicyId"));
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

package com.simplematch.riskservice.admission;

import com.simplematch.marketreference.ArtifactIdentity;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted delivery route associated with one admission journal entry.
 *
 * @param routingPartition explicit Kafka partition, or {@code null} when the producer assigns it
 * @param routingPolicyId retired Routing Policy identity retained only for historical rows
 * @param artifactIdentity immutable daily artifact identity, or {@code null} for a historical row
 * @param routingAlgorithmVersion artifact-declared routing algorithm, paired with artifact identity
 */
public record AdmissionDeliveryRoute(
    Integer routingPartition,
    UUID routingPolicyId,
    ArtifactIdentity artifactIdentity,
    String routingAlgorithmVersion) {
  /** Validates an optional non-negative partition and internally consistent route provenance. */
  public AdmissionDeliveryRoute {
    if (routingPartition != null && routingPartition < 0) {
      throw new IllegalArgumentException("routing_partition must be >= 0");
    }
    if ((artifactIdentity == null) != (routingAlgorithmVersion == null)) {
      throw new IllegalArgumentException(
          "artifact identity and routing algorithm version must be present together");
    }
    if (routingAlgorithmVersion != null && routingAlgorithmVersion.isBlank()) {
      throw new IllegalArgumentException("routing algorithm version must not be blank");
    }
  }

  /** Preserves the one-field constructor used by legacy journal fixtures. */
  public AdmissionDeliveryRoute(Integer routingPartition) {
    this(routingPartition, null, null, null);
  }

  /** Preserves rehydration of an old Routing Policy-backed journal row. */
  public AdmissionDeliveryRoute(Integer routingPartition, UUID routingPolicyId) {
    this(routingPartition, routingPolicyId, null, null);
  }

  /** Returns a route without an explicit partition assignment. */
  public static AdmissionDeliveryRoute unassigned() {
    return new AdmissionDeliveryRoute(null, null, null, null);
  }

  /** Returns a route with an explicit partition assignment. */
  public static AdmissionDeliveryRoute assigned(int routingPartition) {
    return new AdmissionDeliveryRoute(routingPartition, null, null, null);
  }

  /** Returns a route selected by an authoritative policy. */
  public static AdmissionDeliveryRoute assigned(UUID routingPolicyId, int routingPartition) {
    return new AdmissionDeliveryRoute(
        routingPartition, Objects.requireNonNull(routingPolicyId, "routingPolicyId"), null, null);
  }

  /** Returns the final artifact-backed route required for all new Matching deliveries. */
  public static AdmissionDeliveryRoute assigned(
      ArtifactIdentity artifactIdentity, String routingAlgorithmVersion, int routingPartition) {
    return new AdmissionDeliveryRoute(
        routingPartition,
        null,
        Objects.requireNonNull(artifactIdentity, "artifactIdentity"),
        Objects.requireNonNull(routingAlgorithmVersion, "routingAlgorithmVersion"));
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

  /** Returns the final daily artifact identity or fails closed for an obsolete journal row. */
  public ArtifactIdentity requireArtifactIdentity() {
    if (artifactIdentity == null) {
      throw new IllegalStateException("matching command requires a persisted artifact identity");
    }
    return artifactIdentity;
  }

  /**
   * Returns the artifact-declared algorithm version or fails closed for an obsolete journal row.
   */
  public String requireRoutingAlgorithmVersion() {
    if (routingAlgorithmVersion == null) {
      throw new IllegalStateException(
          "matching command requires a persisted routing algorithm version");
    }
    return routingAlgorithmVersion;
  }
}

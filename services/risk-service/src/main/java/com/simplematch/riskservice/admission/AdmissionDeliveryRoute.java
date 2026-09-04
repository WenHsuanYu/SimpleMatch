package com.simplematch.riskservice.admission;

import com.simplematch.marketreference.ArtifactIdentity;
import java.util.Objects;

/**
 * Persisted delivery route associated with one admission journal entry.
 *
 * @param routingPartition explicit Kafka partition, or {@code null} when the producer assigns it
 * @param artifactIdentity immutable daily artifact identity, or {@code null} for a historical row
 * @param routingAlgorithmVersion artifact-declared routing algorithm, paired with artifact identity
 */
public record AdmissionDeliveryRoute(
    Integer routingPartition,
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

  /** Creates a route with an optional explicit partition and no artifact provenance. */
  public AdmissionDeliveryRoute(Integer routingPartition) {
    this(routingPartition, null, null);
  }

  /** Returns a route without an explicit partition assignment. */
  public static AdmissionDeliveryRoute unassigned() {
    return new AdmissionDeliveryRoute(null, null, null);
  }

  /** Returns a route with an explicit partition assignment. */
  public static AdmissionDeliveryRoute assigned(int routingPartition) {
    return new AdmissionDeliveryRoute(routingPartition, null, null);
  }

  /** Returns the final artifact-backed route required for all new Matching deliveries. */
  public static AdmissionDeliveryRoute assigned(
      ArtifactIdentity artifactIdentity, String routingAlgorithmVersion, int routingPartition) {
    return new AdmissionDeliveryRoute(
        routingPartition,
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

  /** Returns the final daily artifact identity or fails closed for an incomplete journal row. */
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

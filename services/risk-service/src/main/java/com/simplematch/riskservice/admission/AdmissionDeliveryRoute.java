package com.simplematch.riskservice.admission;

import com.simplematch.marketreference.ArtifactIdentity;
import java.util.Objects;

/**
 * Persisted delivery route associated with one admission journal entry.
 *
 * <p>Production persistence and Matching delivery require a partition and complete artifact
 * provenance. This type also represents incomplete routes for isolated lifecycle and failure
 * tests. The resolver supplies complete production routes; the JDBC mapper and outbox factory
 * reject missing routing fields. Construction alone does not verify artifact approval or that an
 * instrument belongs to the partition.
 *
 * @param routingPartition explicit Kafka partition, or {@code null} for an incomplete test route
 * @param artifactIdentity immutable daily artifact identity, or {@code null} for an incomplete
 *     route
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

  /** Creates an incomplete test route; it must not be persisted or published to Matching. */
  public AdmissionDeliveryRoute(Integer routingPartition) {
    this(routingPartition, null, null);
  }

  /** Returns an unassigned route for isolated lifecycle tests, not production persistence. */
  public static AdmissionDeliveryRoute unassigned() {
    return new AdmissionDeliveryRoute(null, null, null);
  }

  /** Returns a partition-only route for tests, including missing-provenance rejection tests. */
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

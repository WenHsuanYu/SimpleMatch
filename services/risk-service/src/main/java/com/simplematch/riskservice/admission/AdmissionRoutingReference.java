package com.simplematch.riskservice.admission;

import java.util.Objects;
import java.util.UUID;

/**
 * Optional market-routing snapshot used by one admission decision.
 *
 * @param snapshotId the explicit present-or-absent routing snapshot identity
 */
public record AdmissionRoutingReference(RoutingSnapshotId snapshotId) {
  /** Requires an explicit routing-snapshot value object. */
  public AdmissionRoutingReference {
    Objects.requireNonNull(snapshotId, "snapshotId");
  }

  /** Optional routing snapshot identity. */
  public record RoutingSnapshotId(UUID value) {
    /** Returns whether a routing snapshot is present. */
    public boolean isPresent() {
      return value != null;
    }
  }
}

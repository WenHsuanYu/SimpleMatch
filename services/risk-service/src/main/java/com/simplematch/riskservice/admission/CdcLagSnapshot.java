package com.simplematch.riskservice.admission;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of one durable CDC delivery-lag metric.
 *
 * @param lagEvents number of durable events not yet confirmed as delivered
 * @param updatedAt time at which the metric was last refreshed
 */
public record CdcLagSnapshot(long lagEvents, Instant updatedAt) {

  /** Validates that the snapshot contains structurally valid metric values. */
  public CdcLagSnapshot {
    if (lagEvents < 0) {
      throw new IllegalArgumentException("lagEvents must not be negative");
    }

    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}

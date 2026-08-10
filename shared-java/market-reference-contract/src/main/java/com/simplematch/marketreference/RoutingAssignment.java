package com.simplematch.marketreference;

import java.util.Objects;

/** Assigns one eligible instrument to one explicit Matching partition. */
public record RoutingAssignment(InstrumentRef instrument, int partitionId) {
  /** Validates the route shape before topology-specific checks. */
  public RoutingAssignment {
    Objects.requireNonNull(instrument, "routing instrument is required");
    if (partitionId < 0) {
      throw new MarketReferenceValidationException("routing partition must not be negative");
    }
  }
}

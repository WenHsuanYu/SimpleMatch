package com.simplematch.marketreference;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Complete artifact-owned routing policy for the fixed Matching fleet. */
public record RoutingPolicy(
    String algorithmVersion,
    int partitionCount,
    int maximumInstrumentsPerPartition,
    List<RoutingAssignment> assignments) {
  /** Validates static topology dimensions and canonical assignment ordering. */
  public RoutingPolicy {
    if (algorithmVersion == null || algorithmVersion.isBlank()) {
      throw new MarketReferenceValidationException("routing algorithm version is required");
    }
    if (partitionCount != 15) {
      throw new MarketReferenceValidationException(
          "routing policy must declare exactly 15 partitions");
    }
    if (maximumInstrumentsPerPartition != 150) {
      throw new MarketReferenceValidationException(
          "routing policy must cap each partition at exactly 150 instruments");
    }
    assignments =
        List.copyOf(
            Objects.requireNonNull(assignments, "routing assignments are required").stream()
                .sorted(Comparator.comparing(RoutingAssignment::instrument))
                .toList());
  }
}

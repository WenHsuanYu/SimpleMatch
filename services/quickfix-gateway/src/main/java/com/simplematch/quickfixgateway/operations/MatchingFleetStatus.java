package com.simplematch.quickfixgateway.operations;

import java.time.Instant;
import java.util.List;

/** Complete gateway-domain observation of the fixed Matching fleet. */
public record MatchingFleetStatus(List<MatchingPartitionStatus> partitions, Instant observedAt) {
  /** Captures an immutable set of observed partition-owner facts. */
  public MatchingFleetStatus {
    partitions = List.copyOf(OperationalStatusValidation.required(partitions, "partitions"));
    observedAt = OperationalStatusValidation.required(observedAt, "observedAt");
  }
}

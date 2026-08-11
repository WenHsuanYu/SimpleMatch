package com.simplematch.quickfixgateway.operations;

import java.time.Duration;
import java.util.Optional;

/** Durable progress reported by one critical consumer for one Matching Events partition. */
public record ConsumerPartitionProgress(
    int partitionId,
    long committedOffset,
    long endOffset,
    Optional<Duration> oldestUnprocessedAge) {
  /** Validates normalized, non-negative Kafka progress facts. */
  public ConsumerPartitionProgress {
    if (partitionId < 0) {
      throw new IllegalArgumentException("partitionId must not be negative");
    }
    committedOffset = OperationalStatusValidation.nonNegative(committedOffset, "committedOffset");
    endOffset = OperationalStatusValidation.nonNegative(endOffset, "endOffset");
    oldestUnprocessedAge =
        OperationalStatusValidation.required(oldestUnprocessedAge, "oldestUnprocessedAge");
    oldestUnprocessedAge.ifPresent(
        age -> OperationalStatusValidation.nonNegative(age, "oldestUnprocessedAge"));
  }
}

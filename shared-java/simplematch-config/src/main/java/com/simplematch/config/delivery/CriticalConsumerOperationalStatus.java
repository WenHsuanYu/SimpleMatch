package com.simplematch.config.delivery;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Service-owned live progress and durable quarantine facts for one critical consumer. */
public record CriticalConsumerOperationalStatus(
    boolean quarantined,
    Map<Integer, Long> committedOffsets,
    Map<Integer, Long> oldestUnprocessedAgeMillis,
    Instant observedAt) {
  /** Defensively owns progress maps and requires a source observation time. */
  public CriticalConsumerOperationalStatus {
    committedOffsets = Map.copyOf(Objects.requireNonNull(committedOffsets, "committedOffsets"));
    oldestUnprocessedAgeMillis =
        Map.copyOf(
            Objects.requireNonNull(
                oldestUnprocessedAgeMillis, "oldestUnprocessedAgeMillis"));
    Objects.requireNonNull(observedAt, "observedAt");
    committedOffsets.forEach(
        (partition, offset) -> requireNonNegative(partition, offset, "committedOffsets"));
    oldestUnprocessedAgeMillis.forEach(
        (partition, age) -> requireNonNegative(partition, age, "oldestUnprocessedAgeMillis"));
  }

  private static void requireNonNegative(Integer partition, Long value, String name) {
    if (partition == null || partition < 0 || value == null || value < 0) {
      throw new IllegalArgumentException(name + " must contain non-negative positions");
    }
  }
}

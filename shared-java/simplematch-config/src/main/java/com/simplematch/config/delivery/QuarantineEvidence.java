package com.simplematch.config.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Durable operator evidence for a critical record that stopped its partition. */
public record QuarantineEvidence(
    String consumerName,
    DeliveryRecord record,
    List<RetryAttempt> retryHistory,
    String reason,
    String recoveryInstructions,
    Instant quarantinedAt) {
  /** Validates and freezes all investigation evidence. */
  public QuarantineEvidence {
    if (consumerName == null || consumerName.isBlank()) {
      throw new IllegalArgumentException("consumer name must not be blank");
    }
    Objects.requireNonNull(record, "quarantined record");
    retryHistory = List.copyOf(Objects.requireNonNull(retryHistory, "retry history"));
    if (retryHistory.isEmpty()) {
      throw new IllegalArgumentException("quarantine requires retry history");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("quarantine reason must not be blank");
    }
    if (recoveryInstructions == null || recoveryInstructions.isBlank()) {
      throw new IllegalArgumentException("recovery instructions must not be blank");
    }
    Objects.requireNonNull(quarantinedAt, "quarantinedAt");
  }

  /** Returns a stable text representation suitable for a durable diagnostics column. */
  public String retryHistoryText() {
    return retryHistory.stream()
        .map(attempt -> attempt.attempt() + ":" + attempt.attemptedAt() + ":" + attempt.reason())
        .reduce((left, right) -> left + "\n" + right)
        .orElseThrow();
  }
}

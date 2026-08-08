package com.simplematch.config.delivery;

import java.time.Instant;
import java.util.Objects;

/** Diagnostic record for a rebuildable projection moved to a dead-letter destination. */
public record DeadLetterEvidence(
    String consumerName,
    DeliveryRecord record,
    int attempts,
    String reason,
    Instant deadLetteredAt) {
  /** Validates diagnostic identity and retry count. */
  public DeadLetterEvidence {
    if (consumerName == null || consumerName.isBlank()) {
      throw new IllegalArgumentException("consumer name must not be blank");
    }
    Objects.requireNonNull(record, "dead-letter record");
    if (attempts <= 0) {
      throw new IllegalArgumentException("dead-letter attempts must be positive");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("dead-letter reason must not be blank");
    }
    Objects.requireNonNull(deadLetteredAt, "deadLetteredAt");
  }
}

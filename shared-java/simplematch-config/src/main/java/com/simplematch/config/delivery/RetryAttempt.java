package com.simplematch.config.delivery;

import java.time.Instant;
import java.util.Objects;

/** One recorded failure attempt for a critical delivery position. */
public record RetryAttempt(int attempt, Instant attemptedAt, String reason) {
  /** Requires a positive attempt number, timestamp, and diagnostic reason. */
  public RetryAttempt {
    if (attempt <= 0) {
      throw new IllegalArgumentException("retry attempt must be positive");
    }
    Objects.requireNonNull(attemptedAt, "attemptedAt");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("retry reason must not be blank");
    }
  }
}

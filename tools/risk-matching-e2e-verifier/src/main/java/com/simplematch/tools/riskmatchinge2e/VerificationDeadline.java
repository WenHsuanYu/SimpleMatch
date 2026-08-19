package com.simplematch.tools.riskmatchinge2e;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Owns the one monotonic timeout budget shared by all RM-1 verifier stages. */
final class VerificationDeadline {
  private final long deadlineNanos;
  private final LongSupplier nanoTime;

  VerificationDeadline(Duration timeout, LongSupplier nanoTime) {
    Objects.requireNonNull(timeout, "timeout is required");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nano time source is required");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    deadlineNanos = Math.addExact(nanoTime.getAsLong(), timeout.toNanos());
  }

  static VerificationDeadline start(Duration timeout) {
    return new VerificationDeadline(timeout, System::nanoTime);
  }

  Duration remaining() {
    final long nanos = deadlineNanos - nanoTime.getAsLong();
    return nanos <= 0 ? Duration.ZERO : Duration.ofNanos(nanos);
  }

  Duration requireRemaining(
      VerificationFailure.Stage stage,
      VerificationFailure.Code code,
      String message) {
    final Duration remaining = remaining();
    if (remaining.isZero()) {
      throw new VerificationFailure(stage, code, message);
    }
    return remaining;
  }
}

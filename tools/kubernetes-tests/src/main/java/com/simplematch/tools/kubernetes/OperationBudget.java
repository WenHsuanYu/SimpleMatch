package com.simplematch.tools.kubernetes;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** Shares one monotonic time budget across the commands of a bounded operation. */
final class OperationBudget {
  private final long deadline;

  private OperationBudget(Duration duration) {
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException("operation budget must be positive");
    }
    deadline = System.nanoTime() + duration.toNanos();
  }

  static OperationBudget start(Duration duration) {
    return new OperationBudget(duration);
  }

  Duration remaining() throws TimeoutException {
    final long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new TimeoutException("operation budget expired");
    }
    return Duration.ofNanos(nanos);
  }

  Duration remaining(Duration maximum) throws TimeoutException {
    final Duration available = remaining();
    return available.compareTo(maximum) < 0 ? available : maximum;
  }
}

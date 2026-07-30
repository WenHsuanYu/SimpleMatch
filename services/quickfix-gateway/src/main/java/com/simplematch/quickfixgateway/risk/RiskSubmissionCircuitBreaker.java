package com.simplematch.quickfixgateway.risk;

import java.time.Clock;

final class RiskSubmissionCircuitBreaker {
  private enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final Clock clock;
  private final int consecutiveFailureThreshold;
  private final long openDurationMillis;
  private final Object monitor = new Object();

  private State state = State.CLOSED;
  private int consecutiveFailures;
  private long openUntilUnixMillis;
  private boolean halfOpenProbeInFlight;

  RiskSubmissionCircuitBreaker(
      Clock clock, int consecutiveFailureThreshold, long openDurationMillis) {
    this.clock = clock;
    this.consecutiveFailureThreshold = consecutiveFailureThreshold;
    this.openDurationMillis = openDurationMillis;
  }

  void acquirePermission() {
    synchronized (monitor) {
      final long now = clock.millis();
      if (state == State.OPEN) {
        if (now < openUntilUnixMillis) {
          throw RiskSubmissionFailure.circuitOpen();
        }
        state = State.HALF_OPEN;
      }

      if (state == State.HALF_OPEN) {
        if (halfOpenProbeInFlight) {
          throw RiskSubmissionFailure.circuitOpen();
        }
        halfOpenProbeInFlight = true;
      }
    }
  }

  void recordSuccess() {
    synchronized (monitor) {
      consecutiveFailures = 0;
      halfOpenProbeInFlight = false;
      state = State.CLOSED;
      openUntilUnixMillis = 0;
    }
  }

  void recordFailure() {
    synchronized (monitor) {
      if (state == State.HALF_OPEN) {
        open();
        return;
      }

      consecutiveFailures += 1;
      if (consecutiveFailures >= consecutiveFailureThreshold) {
        open();
      }
    }
  }

  private void open() {
    state = State.OPEN;
    consecutiveFailures = 0;
    halfOpenProbeInFlight = false;
    openUntilUnixMillis = clock.millis() + openDurationMillis;
  }
}

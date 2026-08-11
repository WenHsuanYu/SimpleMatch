package com.simplematch.quickfixgateway.operations;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

/** Immutable policy for the Gateway's explicit trading-day operational authority. */
public record GatewayOperationalPolicy(
    int requiredConsecutiveOpenEligibleChecks,
    Duration staleStatusAfter,
    Duration warningOldestEventAfter,
    Duration pauseOldestEventAfter,
    ZoneId sessionZone,
    LocalTime sessionCloseTime,
    boolean automaticCloseEnabled) {
  /** Validates the fixed Phase 1 operational safety thresholds. */
  public GatewayOperationalPolicy {
    requiredConsecutiveOpenEligibleChecks =
        OperationalStatusValidation.positive(
            requiredConsecutiveOpenEligibleChecks, "requiredConsecutiveOpenEligibleChecks");
    staleStatusAfter =
        OperationalStatusValidation.nonNegative(staleStatusAfter, "staleStatusAfter");
    warningOldestEventAfter =
        OperationalStatusValidation.nonNegative(warningOldestEventAfter, "warningOldestEventAfter");
    pauseOldestEventAfter =
        OperationalStatusValidation.nonNegative(pauseOldestEventAfter, "pauseOldestEventAfter");
    sessionZone = OperationalStatusValidation.required(sessionZone, "sessionZone");
    sessionCloseTime = OperationalStatusValidation.required(sessionCloseTime, "sessionCloseTime");
    if (pauseOldestEventAfter.compareTo(warningOldestEventAfter) < 0) {
      throw new IllegalArgumentException(
          "pauseOldestEventAfter must not precede warningOldestEventAfter");
    }
  }
}

package com.simplematch.quickfixgateway.operations;

import java.time.Duration;

/** Validated thresholds shared by all Gateway readiness rule groups. */
record TradingSystemReadinessThresholds(
    int expectedPartitionCount,
    Duration staleStatusAfter,
    Duration warningOldestEventAfter,
    Duration pauseOldestEventAfter) {
  TradingSystemReadinessThresholds {
    expectedPartitionCount =
        OperationalStatusValidation.positive(expectedPartitionCount, "expectedPartitionCount");
    staleStatusAfter =
        OperationalStatusValidation.nonNegative(staleStatusAfter, "staleStatusAfter");
    warningOldestEventAfter =
        OperationalStatusValidation.nonNegative(warningOldestEventAfter, "warningOldestEventAfter");
    pauseOldestEventAfter =
        OperationalStatusValidation.nonNegative(pauseOldestEventAfter, "pauseOldestEventAfter");
    if (pauseOldestEventAfter.compareTo(warningOldestEventAfter) < 0) {
      throw new IllegalArgumentException(
          "pauseOldestEventAfter must not precede warningOldestEventAfter");
    }
  }
}

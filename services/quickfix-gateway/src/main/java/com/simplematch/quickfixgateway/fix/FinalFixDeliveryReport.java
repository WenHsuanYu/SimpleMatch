package com.simplematch.quickfixgateway.fix;

import java.util.Objects;

/**
 * Stable FIX Execution Report facts persisted before any uncertain socket delivery.
 *
 * @param executionId stable FIX ExecID
 * @param executionType FIX ExecType
 * @param orderStatus FIX OrdStatus
 * @param lastQuantity filled quantity for this report, or zero for a lifecycle transition
 * @param lastPriceUnits fill price in ten-thousandth TWD units, or zero when not a fill
 * @param cumulativeQuantity total filled quantity after this event
 * @param leavesQuantity open quantity after this event
 * @param averagePriceUnits average execution price in ten-thousandth TWD units
 * @param text client-safe lifecycle explanation
 */
public record FinalFixDeliveryReport(
    String executionId,
    char executionType,
    char orderStatus,
    long lastQuantity,
    long lastPriceUnits,
    long cumulativeQuantity,
    long leavesQuantity,
    long averagePriceUnits,
    String text) {
  /** Requires one complete report representation that can be retried without recomputation. */
  public FinalFixDeliveryReport {
    if (executionId == null || executionId.isBlank() || executionId.length() > 80) {
      throw new IllegalArgumentException("FIX executionId must contain at most 80 characters");
    }
    if (lastQuantity < 0
        || lastPriceUnits < 0
        || cumulativeQuantity < 0
        || leavesQuantity < 0
        || averagePriceUnits < 0) {
      throw new IllegalArgumentException("FIX delivery quantities and prices must not be negative");
    }
    text = Objects.requireNonNullElse(text, "");
    if (text.length() > 128) {
      throw new IllegalArgumentException("FIX delivery text must contain at most 128 characters");
    }
  }
}

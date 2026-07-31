package com.simplematch.accountservice.authority;

import java.math.BigDecimal;

/**
 * Mutable quantity and held-notional state of one reservation lifecycle.
 *
 * <p>For an active reservation, remaining quantity is authority still held. For a rejected
 * reservation, the legacy row retains remaining requested quantity as unfilled request metadata;
 * the rejected outcome and zero reserved notional indicate that no authority is held.
 *
 * @param remainingQuantity quantity not yet filled or released
 * @param filledQuantity quantity already filled
 * @param reservedNotional notional currently held against the account limit
 */
public record ReservationAllocation(
    BigDecimal remainingQuantity, BigDecimal filledQuantity, BigDecimal reservedNotional) {
  /** Requires non-negative lifecycle quantities and held authority. */
  public ReservationAllocation {
    remainingQuantity = nonNegative(remainingQuantity, "remaining_quantity");
    filledQuantity = nonNegative(filledQuantity, "filled_quantity");
    reservedNotional = nonNegative(reservedNotional, "reserved_notional");
  }

  private static BigDecimal nonNegative(BigDecimal value, String field) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
    return value;
  }
}

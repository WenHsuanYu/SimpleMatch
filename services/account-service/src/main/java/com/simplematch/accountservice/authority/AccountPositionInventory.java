package com.simplematch.accountservice.authority;

import java.math.BigDecimal;

/**
 * Long/short inventory and the quantities currently held for reservation.
 *
 * @param longQuantity total long quantity
 * @param shortQuantity total short quantity
 * @param reservedLongQuantity long quantity currently held for reservations
 * @param reservedShortQuantity short quantity currently held for reservations
 */
public record AccountPositionInventory(
    BigDecimal longQuantity,
    BigDecimal shortQuantity,
    BigDecimal reservedLongQuantity,
    BigDecimal reservedShortQuantity) {
  /** Enforces non-negative inventory and reservation bounds. */
  public AccountPositionInventory {
    longQuantity = nonNegative(longQuantity, "long_qty");
    shortQuantity = nonNegative(shortQuantity, "short_qty");
    reservedLongQuantity = nonNegative(reservedLongQuantity, "reserved_long_qty");
    reservedShortQuantity = nonNegative(reservedShortQuantity, "reserved_short_qty");
    if (reservedLongQuantity.compareTo(longQuantity) > 0
        || reservedShortQuantity.compareTo(shortQuantity) > 0) {
      throw new IllegalArgumentException("reserved quantity cannot exceed position quantity");
    }
  }

  private static BigDecimal nonNegative(BigDecimal value, String field) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
    return value;
  }
}

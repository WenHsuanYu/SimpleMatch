package com.simplematch.accountservice.authority;

/**
 * Long/short inventory and the quantities currently held for reservation.
 *
 * @param longQuantity total long quantity
 * @param shortQuantity total short quantity
 * @param reservedLongQuantity long quantity currently held for reservations
 * @param reservedShortQuantity short quantity currently held for reservations
 */
public record AccountPositionInventory(
    long longQuantity,
    long shortQuantity,
    long reservedLongQuantity,
    long reservedShortQuantity) {
  /** Enforces non-negative inventory and reservation bounds. */
  public AccountPositionInventory {
    longQuantity = nonNegative(longQuantity, "long_qty");
    shortQuantity = nonNegative(shortQuantity, "short_qty");
    reservedLongQuantity = nonNegative(reservedLongQuantity, "reserved_long_qty");
    reservedShortQuantity = nonNegative(reservedShortQuantity, "reserved_short_qty");
    if (reservedLongQuantity > longQuantity || reservedShortQuantity > shortQuantity) {
      throw new IllegalArgumentException("reserved quantity cannot exceed position quantity");
    }
  }

  private static long nonNegative(long value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
    return value;
  }
}

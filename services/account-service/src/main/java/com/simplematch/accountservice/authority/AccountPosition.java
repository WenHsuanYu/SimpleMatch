package com.simplematch.accountservice.authority;

import java.math.BigDecimal;

/** Immutable account position and reserved quantity snapshot. */
public record AccountPosition(
    String accountId,
    String symbol,
    BigDecimal longQuantity,
    BigDecimal shortQuantity,
    BigDecimal reservedLongQuantity,
    BigDecimal reservedShortQuantity,
    long version,
    long updatedAtUnixMs) {
  /** Validates non-negative quantities and reserved-quantity bounds. */
  public AccountPosition {
    accountId = text(accountId, "account_id");
    symbol = text(symbol, "symbol");
    longQuantity = nonNegative(longQuantity, "long_qty");
    shortQuantity = nonNegative(shortQuantity, "short_qty");
    reservedLongQuantity = nonNegative(reservedLongQuantity, "reserved_long_qty");
    reservedShortQuantity = nonNegative(reservedShortQuantity, "reserved_short_qty");
    if (reservedLongQuantity.compareTo(longQuantity) > 0
        || reservedShortQuantity.compareTo(shortQuantity) > 0) {
      throw new IllegalArgumentException("reserved quantity cannot exceed position quantity");
    }
    if (version < 0 || updatedAtUnixMs < 0) {
      throw new IllegalArgumentException("version and timestamp must be non-negative");
    }
  }

  /** Returns a provisioned empty position. */
  public static AccountPosition provisioned(String accountId, String symbol, long now) {
    return new AccountPosition(
        accountId,
        symbol,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0,
        now);
  }

  private static String text(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static BigDecimal nonNegative(BigDecimal value, String name) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
    return value;
  }
}

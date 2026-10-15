package com.simplematch.accountservice.authority;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact TWD amount validation for reservation contracts and wider account aggregates. */
public final class AccountAmounts {
  private static final int TWD_SCALE = 4;
  private static final int AGGREGATE_PRECISION = 38;
  private static final BigDecimal MAX_RESERVATION_AMOUNT =
      BigDecimal.valueOf(Long.MAX_VALUE, TWD_SCALE);

  private AccountAmounts() {}

  /**
   * Requires a non-negative TWD amount representable in signed-long fixed point contract units.
   *
   * @param value amount in TWD
   * @param field field name used in validation failures
   * @return exact amount normalized to four decimal places
   * @throws IllegalArgumentException when precision, sign, or contract range is invalid
   */
  public static BigDecimal reservationAmount(BigDecimal value, String field) {
    final BigDecimal normalized = aggregateAmount(value, field);
    if (normalized.compareTo(MAX_RESERVATION_AMOUNT) > 0) {
      throw new IllegalArgumentException(field + " exceeds fixed-point range");
    }
    return normalized;
  }

  /**
   * Requires an exact non-negative aggregate amount bounded by PostgreSQL NUMERIC(38,4).
   *
   * <p>Account totals may sum many signed-long reservations, so they intentionally retain a wider
   * exact decimal range rather than accumulating into a signed long.
   *
   * @param value amount in TWD
   * @param field field name used in validation failures
   * @return exact amount normalized to four decimal places
   * @throws IllegalArgumentException when precision, sign, or aggregate range is invalid
   */
  public static BigDecimal aggregateAmount(BigDecimal value, String field) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
    final BigDecimal normalized;
    try {
      normalized = value.setScale(TWD_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException precision) {
      throw new IllegalArgumentException(
          field + " must use at most four decimal places", precision);
    }
    if (normalized.precision() > AGGREGATE_PRECISION) {
      throw new IllegalArgumentException(field + " exceeds aggregate range");
    }
    return normalized;
  }
}

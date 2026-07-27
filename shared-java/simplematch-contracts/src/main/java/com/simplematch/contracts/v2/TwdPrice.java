package com.simplematch.contracts.v2;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A positive TWD price represented in one ten-thousandth TWD units. */
public record TwdPrice(long units) {
  public static final int SCALE = 4;

  public TwdPrice {
    if (units <= 0) {
      throw new DomainValidationException("price units must be positive");
    }
  }

  /** Parses a decimal price without rounding it. */
  public static TwdPrice ofDecimal(String value) {
    try {
      final BigDecimal decimal = new BigDecimal(value).setScale(SCALE, RoundingMode.UNNECESSARY);
      return new TwdPrice(decimal.movePointRight(SCALE).longValueExact());
    } catch (ArithmeticException | NumberFormatException | NullPointerException exception) {
      throw new DomainValidationException("price must use at most " + SCALE + " decimal places");
    }
  }

  /** Returns the canonical non-exponent decimal representation. */
  public String toDecimalString() {
    return BigDecimal.valueOf(units, SCALE).stripTrailingZeros().toPlainString();
  }
}

package com.simplematch.marketreference.builder;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Converts exchange decimal prices into exact one-ten-thousandth TWD units. */
final class TwdUnits {
  private static final int SCALE = 4;

  private TwdUnits() {}

  static long parsePositive(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new MarketReferenceBuildException(fieldName + " is required");
    }
    try {
      final long units =
          new BigDecimal(value.trim())
              .setScale(SCALE, RoundingMode.UNNECESSARY)
              .movePointRight(SCALE)
              .longValueExact();
      if (units <= 0) {
        throw new MarketReferenceBuildException(fieldName + " must be a positive TWD price");
      }
      return units;
    } catch (ArithmeticException | NumberFormatException exception) {
      throw new MarketReferenceBuildException(
          fieldName + " must be a TWD price with at most four decimal places", exception);
    }
  }
}

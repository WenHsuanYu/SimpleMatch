package com.simplematch.quickfixgateway.fix;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Normalizes domain values into their stable FIX 4.4 wire representations. */
final class FixWireValues {
  private static final DateTimeFormatter FIX_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

  private FixWireValues() {}

  static char mapOrderSide(com.simplematch.contracts.common.v2.Side side) {
    return switch (side) {
      case SIDE_SELL -> '2';
      case SIDE_BUY, SIDE_UNSPECIFIED -> '1';
      default -> '1';
    };
  }

  static int mapCancelRejectReason(String text) {
    if (text == null || text.isBlank()) {
      return 99;
    }
    final String normalized = text.toUpperCase(Locale.ROOT);
    if (normalized.contains("UNKNOWN_ORDER")) {
      return 1;
    }
    if (normalized.contains("TOO_LATE") || normalized.contains("TOO LATE")) {
      return 0;
    }
    if (normalized.contains("DUPLICATE")) {
      return 6;
    }
    return 99;
  }

  static String format(Instant instant) {
    return FIX_TIMESTAMP.format(instant);
  }

  static String normalizeDecimal(String value) {
    if (value == null || value.isBlank()) {
      return "0";
    }
    return new BigDecimal(value).stripTrailingZeros().toPlainString();
  }
}

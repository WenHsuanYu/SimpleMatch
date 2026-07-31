package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Normalizes domain values into their stable FIX 4.4 wire representations. */
final class FixWireValues {
  private static final DateTimeFormatter FIX_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

  private FixWireValues() {}

  static char mapSide(Side side) {
    return switch (side) {
      case SIDE_SELL -> '2';
      case SIDE_BUY, SIDE_UNSPECIFIED -> '1';
      default -> '1';
    };
  }

  static char mapExecType(ExecutionType executionType) {
    return switch (executionType) {
      case EXECUTION_TYPE_PENDING_NEW -> 'A';
      case EXECUTION_TYPE_NEW -> '0';
      case EXECUTION_TYPE_PARTIAL_FILL -> '1';
      case EXECUTION_TYPE_FILL -> '2';
      case EXECUTION_TYPE_CANCELED -> '4';
      case EXECUTION_TYPE_REJECTED,
          EXECUTION_TYPE_CANCEL_REJECTED,
          EXECUTION_TYPE_UNSPECIFIED -> '8';
      default -> '8';
    };
  }

  static char mapOrdStatus(ExecutionType executionType) {
    return mapExecType(executionType);
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

  static String clientOrderIdForExecution(ExecutionEvent executionEvent) {
    if (executionEvent.getExecutionType() == ExecutionType.EXECUTION_TYPE_CANCELED
        && !executionEvent.getCancelClOrdId().isBlank()) {
      return executionEvent.getCancelClOrdId();
    }
    return executionEvent.getClOrdId();
  }

  static String transactTime(ExecutionEvent executionEvent, Clock clock) {
    if (executionEvent.hasMetadata() && executionEvent.getMetadata().getCreatedAtUnixMs() > 0) {
      return format(Instant.ofEpochMilli(executionEvent.getMetadata().getCreatedAtUnixMs()));
    }
    return format(Instant.now(clock));
  }

  static String format(Instant instant) {
    return FIX_TIMESTAMP.format(instant);
  }

  static String fallbackDecimal(String value, String fallback) {
    return normalizeDecimal(value != null && !value.isBlank() ? value : fallback);
  }

  static String normalizeDecimal(String value) {
    if (value == null || value.isBlank()) {
      return "0";
    }
    return new BigDecimal(value).stripTrailingZeros().toPlainString();
  }
}

package com.simplematch.quickfixgateway.wal;

import java.math.BigDecimal;
import java.util.Objects;

/** Shared validation rules for gateway-local WAL values. */
final class WalValidation {
  private static final int MAX_TEXT_LENGTH = 64;
  private static final int MAX_ORDER_ID_LENGTH = MAX_TEXT_LENGTH + 2;

  private WalValidation() {}

  static String requiredText(String value, String fieldName) {
    return requiredText(value, fieldName, MAX_TEXT_LENGTH);
  }

  private static String requiredText(String value, String fieldName, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          fieldName + " must be <= " + maximumLength + " characters");
    }
    return value;
  }

  static String requiredOrderId(String value) {
    return requiredText(value, "order_id", MAX_ORDER_ID_LENGTH);
  }

  static String nonBlankText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  static String optionalText(String value, String fieldName) {
    final String normalized = Objects.requireNonNullElse(value, "");
    if (normalized.length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException(fieldName + " must be <= 64 characters");
    }
    return normalized;
  }

  static String positiveDecimal(String value, String fieldName) {
    final String normalized = requiredText(value, fieldName);
    try {
      if (new BigDecimal(normalized).signum() <= 0) {
        throw new IllegalArgumentException(fieldName + " must be positive");
      }
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(fieldName + " must be a decimal", exception);
    }
    return normalized;
  }

  static String optionalPositiveDecimal(String value, String fieldName) {
    final String normalized = Objects.requireNonNullElse(value, "");
    return normalized.isBlank() ? "" : positiveDecimal(normalized, fieldName);
  }
}

package com.simplematch.quickfixgateway.operations;

import java.time.Duration;
import java.util.Objects;

/** Shared validation for immutable Gateway operational-status carriers. */
final class OperationalStatusValidation {
  private OperationalStatusValidation() {}

  static String requiredText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  static <T> T required(T value, String name) {
    return Objects.requireNonNull(value, name + " is required");
  }

  static long nonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }

  static Duration nonNegative(Duration value, String name) {
    final Duration requiredDuration = required(value, name);
    if (requiredDuration.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return requiredDuration;
  }

  static int positive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}

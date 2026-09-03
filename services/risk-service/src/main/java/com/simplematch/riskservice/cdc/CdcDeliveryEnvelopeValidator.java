package com.simplematch.riskservice.cdc;

import java.util.Objects;

/** Validates and owns the mutable payload boundary for a CDC envelope. */
final class CdcDeliveryEnvelopeValidator {
  private CdcDeliveryEnvelopeValidator() {}

  static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  static byte[] copyPayload(byte[] value) {
    Objects.requireNonNull(value, "payload");
    if (value.length == 0) {
      throw new IllegalArgumentException("payload must not be empty");
    }
    return value.clone();
  }

  static long requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }
}

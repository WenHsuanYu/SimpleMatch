package com.simplematch.riskservice.admission;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Parses scalar admission fields while preserving their stable validation failures. */
final class AdmissionFieldParser {
  private AdmissionFieldParser() {}

  static UUID uuid(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidCommand(field + " is required"));
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidCommand(field + " must be a UUID"));
    }
  }

  static long positive(long value, String field) {
    if (value <= 0) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidCommand(field + " must be positive"));
    }
    return value;
  }

  static LocalDate requiredTradingDay(String value) {
    if (value.isBlank()) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidCommand("trading_day is required"));
    }
    return isoTradingDay(value);
  }

  static LocalDate isoTradingDay(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidCommand("trading_day must be ISO-8601"));
    }
  }
}

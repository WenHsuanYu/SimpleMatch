package com.simplematch.marketreference.builder;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Parses ISO and official Republic of China calendar dates without locale dependence. */
final class OfficialDateParser {
  private static final int ROC_YEAR_OFFSET = 1911;
  private static final int ROC_DATE_LENGTH = 7;

  private OfficialDateParser() {}

  static LocalDate parse(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new MarketReferenceBuildException(fieldName + " is required");
    }
    final String normalized = value.trim();
    try {
      return isRocDate(normalized) ? parseRocDate(normalized) : LocalDate.parse(normalized);
    } catch (DateTimeParseException | NumberFormatException exception) {
      throw new MarketReferenceBuildException(fieldName + " must be an ISO or ROC date", exception);
    }
  }

  private static boolean isRocDate(String value) {
    return value.length() == ROC_DATE_LENGTH && value.chars().allMatch(Character::isDigit);
  }

  private static LocalDate parseRocDate(String value) {
    final int year = Integer.parseInt(value.substring(0, 3)) + ROC_YEAR_OFFSET;
    final int month = Integer.parseInt(value.substring(3, 5));
    final int day = Integer.parseInt(value.substring(5, 7));
    return LocalDate.of(year, month, day);
  }
}

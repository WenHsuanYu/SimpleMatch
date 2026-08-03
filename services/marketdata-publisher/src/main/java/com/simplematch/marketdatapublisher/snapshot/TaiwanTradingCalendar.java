package com.simplematch.marketdatapublisher.snapshot;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Validates whether a date is an eligible Taiwan trading day. */
final class TaiwanTradingCalendar {
  private final List<LocalDate> holidays;

  TaiwanTradingCalendar(List<String> rawHolidays) {
    holidays = rawHolidays.stream().map(TaiwanTradingCalendar::parseHoliday).toList();
  }

  boolean isTradingDay(LocalDate day) {
    return day.getDayOfWeek() != DayOfWeek.SATURDAY
        && day.getDayOfWeek() != DayOfWeek.SUNDAY
        && !holidays.contains(day);
  }

  private static LocalDate parseHoliday(String value) {
    if (value == null || value.isBlank()) {
      throw new MarketSnapshotValidationException("holiday must be an ISO-8601 date");
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw new MarketSnapshotValidationException("holiday must be an ISO-8601 date", exception);
    }
  }
}

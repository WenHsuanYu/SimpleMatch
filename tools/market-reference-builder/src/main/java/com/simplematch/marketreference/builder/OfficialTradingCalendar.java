package com.simplematch.marketreference.builder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/** Official calendar coverage used to validate a target trading day and its prior session. */
final class OfficialTradingCalendar {
  private final Set<LocalDate> dates;
  private final Set<LocalDate> nonTradingDays;

  OfficialTradingCalendar(Set<LocalDate> dates, Set<LocalDate> nonTradingDays) {
    this.dates = Set.copyOf(Objects.requireNonNull(dates, "calendar dates are required"));
    this.nonTradingDays =
        Set.copyOf(Objects.requireNonNull(nonTradingDays, "calendar closures are required"));
  }

  boolean coversYear(int year) {
    return dates.stream().anyMatch(date -> date.getYear() == year);
  }

  boolean isTradingDay(LocalDate day) {
    return day.getDayOfWeek() != DayOfWeek.SATURDAY
        && day.getDayOfWeek() != DayOfWeek.SUNDAY
        && !nonTradingDays.contains(day);
  }

  LocalDate priorTradingDay(LocalDate tradingDay) {
    LocalDate candidate = tradingDay.minusDays(1);
    while (!isTradingDay(candidate)) {
      candidate = candidate.minusDays(1);
    }
    return candidate;
  }
}

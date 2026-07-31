package com.simplematch.contracts.v2;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** A Taiwan market date, distinct from an absolute event timestamp. */
public record TradingDay(LocalDate value) {
  /** Creates a validated trading day. */
  public TradingDay {
    Objects.requireNonNull(value, "trading day is required");
  }

  /** Parses an ISO-8601 calendar date. */
  public static TradingDay parse(String value) {
    if (value == null) {
      throw new DomainValidationException("trading_day must be an ISO-8601 date");
    }
    try {
      return new TradingDay(LocalDate.parse(value));
    } catch (DateTimeParseException exception) {
      throw new DomainValidationException("trading_day must be an ISO-8601 date");
    }
  }
}

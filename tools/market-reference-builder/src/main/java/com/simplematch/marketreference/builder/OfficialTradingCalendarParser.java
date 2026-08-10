package com.simplematch.marketreference.builder;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** Parses TWSE calendar closures while preserving explicit trading-day entries. */
final class OfficialTradingCalendarParser {
  private final OfficialJsonRows rows;

  OfficialTradingCalendarParser(OfficialJsonRows rows) {
    this.rows = rows;
  }

  OfficialTradingCalendar parse(RetrievedOfficialSource source) {
    final Set<LocalDate> dates = new HashSet<>();
    final Set<LocalDate> nonTradingDays = new HashSet<>();
    for (JsonNode row : rows.array(source, "TWSE trading calendar")) {
      final LocalDate date =
          OfficialDateParser.parse(
              rows.requiredText(row, "Date", "TWSE calendar"), "calendar date");
      if (!dates.add(date)) {
        throw new MarketReferenceBuildException("TWSE trading calendar contains a duplicate date");
      }
      if (indicatesNonTradingDay(row)) {
        nonTradingDays.add(date);
      }
    }
    return new OfficialTradingCalendar(dates, nonTradingDays);
  }

  private boolean indicatesNonTradingDay(JsonNode row) {
    final String details = rows.optionalText(row, "Name") + ' '
        + rows.optionalText(row, "Description");
    if (details.contains("開始交易") || details.contains("最後交易")) {
      return false;
    }
    return details.contains("放假")
        || details.contains("無交易")
        || details.contains("休市")
        || details.contains("停止交易");
  }
}

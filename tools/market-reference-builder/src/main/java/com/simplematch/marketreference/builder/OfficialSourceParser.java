package com.simplematch.marketreference.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** Facade for the bounded official JSON source parsers used by the normalizer. */
final class OfficialSourceParser {
  private final OfficialCompanySourceParser companyParser;
  private final OfficialPriceSourceParser priceParser;
  private final OfficialTradingCalendarParser calendarParser;

  OfficialSourceParser(ObjectMapper objectMapper) {
    final OfficialJsonRows rows = new OfficialJsonRows(objectMapper);
    this.companyParser = new OfficialCompanySourceParser(rows);
    this.priceParser = new OfficialPriceSourceParser(rows);
    this.calendarParser = new OfficialTradingCalendarParser(rows);
  }

  CompanySource parseTwseCompanies(RetrievedOfficialSource source) {
    return companyParser.parseTwse(source);
  }

  CompanySource parseTpexCompanies(RetrievedOfficialSource source) {
    return companyParser.parseTpex(source);
  }

  PriceSource parseTwseDailyLimits(RetrievedOfficialSource source) {
    return priceParser.parseTwse(source);
  }

  PriceSource parseTpexDailyLimits(RetrievedOfficialSource source) {
    return priceParser.parseTpex(source);
  }

  OfficialTradingCalendar parseTradingCalendar(RetrievedOfficialSource source) {
    return calendarParser.parse(source);
  }

  record CompanySource(String venueMic, LocalDate sourceDate, Set<String> symbols) {
    CompanySource {
      symbols = Set.copyOf(symbols);
    }
  }

  record PriceSource(
      String venueMic,
      LocalDate uniformDocumentDate,
      Set<LocalDate> perInstrumentLastTradingDays,
      Map<String, PriceFacts> prices) {
    PriceSource {
      perInstrumentLastTradingDays = Set.copyOf(perInstrumentLastTradingDays);
      prices = Map.copyOf(prices);
      if (uniformDocumentDate == null && perInstrumentLastTradingDays.isEmpty()) {
        throw new MarketReferenceBuildException("price source must declare a date contract");
      }
      if (uniformDocumentDate != null && !perInstrumentLastTradingDays.isEmpty()) {
        throw new MarketReferenceBuildException("price source cannot use two date contracts");
      }
    }
  }
}

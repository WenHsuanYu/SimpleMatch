package com.simplematch.marketreference.builder;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Parses and validates official daily reference-price and price-limit documents. */
final class OfficialPriceSourceParser {
  private static final PriceSpec TWSE =
      new PriceSpec(
          "XTAI",
          "LastTradingDay",
          "Code",
          "TodayOpeningRefPrice",
          "TodayLimitDown",
          "TodayLimitUp",
          DateContract.PER_INSTRUMENT_LAST_TRADING_DAY,
          "TWSE daily price-limit source");
  private static final PriceSpec TPEX =
      new PriceSpec(
          "ROCO",
          "Date",
          "SecuritiesCompanyCode",
          "NextReferencePrice",
          "NextLimitDown",
          "NextLimitUp",
          DateContract.UNIFORM_DOCUMENT_DATE,
          "TPEx daily price-limit source");
  private final OfficialJsonRows rows;

  OfficialPriceSourceParser(OfficialJsonRows rows) {
    this.rows = rows;
  }

  OfficialSourceParser.PriceSource parseTwse(RetrievedOfficialSource source) {
    return parse(source, TWSE);
  }

  OfficialSourceParser.PriceSource parseTpex(RetrievedOfficialSource source) {
    return parse(source, TPEX);
  }

  private OfficialSourceParser.PriceSource parse(
      RetrievedOfficialSource source, PriceSpec specification) {
    LocalDate uniformDocumentDate = null;
    final Set<LocalDate> perInstrumentLastTradingDays = new HashSet<>();
    final Map<String, PriceFacts> prices = new HashMap<>();
    for (JsonNode row : rows.array(source, specification.label())) {
      uniformDocumentDate =
          collectDate(
              uniformDocumentDate, perInstrumentLastTradingDays, row, specification);
      final String symbol =
          rows.requiredText(row, specification.symbolField(), specification.label());
      if (prices.put(symbol, priceFacts(row, specification)) != null) {
        throw new MarketReferenceBuildException(
            specification.label() + " contains a duplicate price instrument");
      }
    }
    return new OfficialSourceParser.PriceSource(
        specification.venueMic(),
        requireUniformDocumentDate(
            uniformDocumentDate, perInstrumentLastTradingDays, specification),
        perInstrumentLastTradingDays,
        prices);
  }

  private PriceFacts priceFacts(JsonNode row, PriceSpec specification) {
    return new PriceFacts(
        price(row, specification.referenceField(), specification),
        price(row, specification.lowerLimitField(), specification),
        price(row, specification.upperLimitField(), specification));
  }

  private long price(JsonNode row, String fieldName, PriceSpec specification) {
    return TwdUnits.parsePositive(
        rows.requiredText(row, fieldName, specification.label()), fieldName);
  }

  private LocalDate collectDate(
      LocalDate current,
      Set<LocalDate> perInstrumentLastTradingDays,
      JsonNode row,
      PriceSpec specification) {
    final LocalDate rowDate =
        OfficialDateParser.parse(
            rows.requiredText(row, specification.dateField(), specification.label()),
            specification.dateField());
    if (specification.dateContract() == DateContract.PER_INSTRUMENT_LAST_TRADING_DAY) {
      perInstrumentLastTradingDays.add(rowDate);
      return current;
    }
    if (current != null && !current.equals(rowDate)) {
      throw new MarketReferenceBuildException(
          specification.label() + " contains inconsistent source dates");
    }
    return rowDate;
  }

  private LocalDate requireUniformDocumentDate(
      LocalDate uniformDocumentDate,
      Set<LocalDate> perInstrumentLastTradingDays,
      PriceSpec specification) {
    if (specification.dateContract() == DateContract.PER_INSTRUMENT_LAST_TRADING_DAY) {
      if (perInstrumentLastTradingDays.isEmpty()) {
        throw new MarketReferenceBuildException(specification.label() + " contains no source date");
      }
      return null;
    }
    if (uniformDocumentDate == null) {
      throw new MarketReferenceBuildException(specification.label() + " contains no source date");
    }
    return uniformDocumentDate;
  }

  private record PriceSpec(
      String venueMic,
      String dateField,
      String symbolField,
      String referenceField,
      String lowerLimitField,
      String upperLimitField,
      DateContract dateContract,
      String label) {}

  private enum DateContract {
    UNIFORM_DOCUMENT_DATE,
    PER_INSTRUMENT_LAST_TRADING_DAY
  }
}

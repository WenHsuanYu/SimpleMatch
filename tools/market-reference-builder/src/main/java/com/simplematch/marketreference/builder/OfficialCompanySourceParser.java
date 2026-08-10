package com.simplematch.marketreference.builder;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** Parses and validates the official TWSE and TPEx listed-company registries. */
final class OfficialCompanySourceParser {
  private static final CompanySpec TWSE =
      new CompanySpec("XTAI", "出表日期", "公司代號", "TWSE company source");
  private static final CompanySpec TPEX =
      new CompanySpec("ROCO", "Date", "SecuritiesCompanyCode", "TPEx company source");
  private final OfficialJsonRows rows;

  OfficialCompanySourceParser(OfficialJsonRows rows) {
    this.rows = rows;
  }

  OfficialSourceParser.CompanySource parseTwse(RetrievedOfficialSource source) {
    return parse(source, TWSE);
  }

  OfficialSourceParser.CompanySource parseTpex(RetrievedOfficialSource source) {
    return parse(source, TPEX);
  }

  private OfficialSourceParser.CompanySource parse(
      RetrievedOfficialSource source, CompanySpec specification) {
    LocalDate sourceDate = null;
    final Set<String> symbols = new HashSet<>();
    for (JsonNode row : rows.array(source, specification.label())) {
      sourceDate = sourceDate(sourceDate, row, specification);
      final String symbol =
          rows.requiredText(row, specification.symbolField(), specification.label());
      if (!symbols.add(symbol)) {
        throw new MarketReferenceBuildException(
            specification.label() + " contains a duplicate company instrument");
      }
    }
    return new OfficialSourceParser.CompanySource(
        specification.venueMic(), requireSourceDate(sourceDate, specification), symbols);
  }

  private LocalDate sourceDate(
      LocalDate current, JsonNode row, CompanySpec specification) {
    final LocalDate rowDate =
        OfficialDateParser.parse(
            rows.requiredText(row, specification.dateField(), specification.label()),
            specification.dateField());
    if (current != null && !current.equals(rowDate)) {
      throw new MarketReferenceBuildException(
          specification.label() + " contains inconsistent source dates");
    }
    return rowDate;
  }

  private LocalDate requireSourceDate(LocalDate sourceDate, CompanySpec specification) {
    if (sourceDate == null) {
      throw new MarketReferenceBuildException(specification.label() + " contains no source date");
    }
    return sourceDate;
  }

  private record CompanySpec(String venueMic, String dateField, String symbolField, String label) {}
}

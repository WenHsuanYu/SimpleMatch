package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.ArtifactReleaseState;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** Applies the fail-closed source-date contract before any artifact construction. */
final class OfficialSourceFreshnessValidator {
  private static final int MAXIMUM_COMPANY_SOURCE_AGE_DAYS = 7;

  void validate(
      LocalDate tradingDay,
      ArtifactReleaseState releaseState,
      OfficialTradingCalendar calendar,
      RetrievedOfficialSource twseDailyLimitsDocument,
      OfficialSourceParser.CompanySource twseCompanies,
      OfficialSourceParser.CompanySource tpexCompanies,
      OfficialSourceParser.PriceSource twsePrices,
      OfficialSourceParser.PriceSource tpexPrices) {
    Objects.requireNonNull(tradingDay, "trading day is required");
    Objects.requireNonNull(releaseState, "artifact release state is required");
    requireCalendarCoverage(tradingDay, calendar);
    final LocalDate priorTradingDay = calendar.priorTradingDay(tradingDay);
    requireTwseLastTradingDays(twsePrices.perInstrumentLastTradingDays(), priorTradingDay);
    requireDailyPriceDate(tpexPrices.uniformDocumentDate(), priorTradingDay, "TPEx");
    requireFinalTwseRetrievalDate(releaseState, twseDailyLimitsDocument, tradingDay);
    requireFreshCompanySources(tradingDay, List.of(twseCompanies, tpexCompanies));
  }

  private void requireTwseLastTradingDays(
      java.util.Set<LocalDate> lastTradingDays, LocalDate priorTradingDay) {
    for (LocalDate lastTradingDay : lastTradingDays) {
      if (lastTradingDay.isAfter(priorTradingDay)) {
        throw new MarketReferenceBuildException(
            "TWSE daily price source contains a future last-trading-day");
      }
    }
  }

  private void requireFinalTwseRetrievalDate(
      ArtifactReleaseState releaseState,
      RetrievedOfficialSource twseDailyLimitsDocument,
      LocalDate tradingDay) {
    if (releaseState != ArtifactReleaseState.FINAL) {
      return;
    }
    final LocalDate retrievedDate =
        twseDailyLimitsDocument.retrievedAt().atZone(ZoneId.of("Asia/Taipei")).toLocalDate();
    if (!retrievedDate.equals(tradingDay)) {
      throw new MarketReferenceBuildException(
          "TWSE daily price source must be retrieved on the target trading day");
    }
  }

  private void requireCalendarCoverage(LocalDate tradingDay, OfficialTradingCalendar calendar) {
    if (!calendar.coversYear(tradingDay.getYear())) {
      throw new MarketReferenceBuildException(
          "official trading calendar does not cover trading year");
    }
    if (!calendar.isTradingDay(tradingDay)) {
      throw new MarketReferenceBuildException("requested day is not an official trading day");
    }
  }

  private void requireDailyPriceDate(
      LocalDate actualSourceDate, LocalDate priorTradingDay, String venueName) {
    if (!actualSourceDate.equals(priorTradingDay)) {
      throw new MarketReferenceBuildException(
          venueName + " daily price source must identify the prior trading day");
    }
  }

  private void requireFreshCompanySources(
      LocalDate tradingDay, List<OfficialSourceParser.CompanySource> companySources) {
    final LocalDate oldestAllowed = tradingDay.minusDays(MAXIMUM_COMPANY_SOURCE_AGE_DAYS);
    for (OfficialSourceParser.CompanySource source : companySources) {
      if (source.sourceDate().isBefore(oldestAllowed) || source.sourceDate().isAfter(tradingDay)) {
        throw new MarketReferenceBuildException("company source is stale or from the future");
      }
    }
  }
}

package com.simplematch.marketreference.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.ArtifactInstrument;
import com.simplematch.marketreference.ArtifactReleaseState;
import com.simplematch.marketreference.InstrumentEligibility;
import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.marketreference.SourceProvenance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Reconciles official source documents into pure Phase 1 instrument facts. */
public final class OfficialMarketDataNormalizer {
  private final OfficialSourceParser parser;
  private final OfficialSourceFreshnessValidator freshnessValidator;

  /** Creates a strict normalizer that has no Spring or trading-path dependency. */
  public OfficialMarketDataNormalizer(ObjectMapper objectMapper) {
    this.parser = new OfficialSourceParser(objectMapper);
    this.freshnessValidator = new OfficialSourceFreshnessValidator();
  }

  /**
   * Reconciles all official inputs for one target release state.
   *
   * @param sources exact bounded official documents
   * @param tradingDay requested Asia/Taipei trading day
   * @param releaseState preliminary or final artifact state
   * @return immutable deterministic normalized market data
   */
  public NormalizedOfficialMarketData normalize(
      OfficialMarketDataSources sources, LocalDate tradingDay, ArtifactReleaseState releaseState) {
    Objects.requireNonNull(sources, "official source documents are required");
    Objects.requireNonNull(tradingDay, "trading day is required");
    Objects.requireNonNull(releaseState, "artifact release state is required");
    final ParsedOfficialData parsed = parseSources(sources);
    freshnessValidator.validate(
        tradingDay,
        releaseState,
        parsed.calendar(),
        sources.document(OfficialSourceType.TWSE_DAILY_LIMITS),
        parsed.twseCompanies(),
        parsed.tpexCompanies(),
        parsed.twsePrices(),
        parsed.tpexPrices());
    return new NormalizedOfficialMarketData(
        PhaseOneMarketRules.marketRules(),
        reconcileUniverse(parsed, releaseState),
        sourceProvenance(sources, parsed, tradingDay));
  }

  private ParsedOfficialData parseSources(OfficialMarketDataSources sources) {
    return new ParsedOfficialData(
        parser.parseTwseCompanies(sources.document(OfficialSourceType.TWSE_COMPANIES)),
        parser.parseTpexCompanies(sources.document(OfficialSourceType.TPEX_COMPANIES)),
        parser.parseTwseDailyLimits(sources.document(OfficialSourceType.TWSE_DAILY_LIMITS)),
        parser.parseTpexDailyLimits(sources.document(OfficialSourceType.TPEX_DAILY_LIMITS)),
        parser.parseTradingCalendar(sources.document(OfficialSourceType.TWSE_TRADING_CALENDAR)));
  }

  private List<ArtifactInstrument> reconcileUniverse(
      ParsedOfficialData parsed, ArtifactReleaseState releaseState) {
    final List<ArtifactInstrument> instruments = new ArrayList<>();
    instruments.addAll(
        reconcileVenue(
            parsed.twseCompanies(), parsed.twsePrices(), releaseState));
    instruments.addAll(
        reconcileVenue(
            parsed.tpexCompanies(), parsed.tpexPrices(), releaseState));
    return List.copyOf(instruments);
  }

  private List<ArtifactInstrument> reconcileVenue(
      OfficialSourceParser.CompanySource companies,
      OfficialSourceParser.PriceSource prices,
      ArtifactReleaseState releaseState) {
    return new TreeSet<>(companies.symbols()).stream()
        .map(symbol -> instrument(companies, prices, symbol, releaseState))
        .toList();
  }

  private ArtifactInstrument instrument(
      OfficialSourceParser.CompanySource companies,
      OfficialSourceParser.PriceSource prices,
      String symbol,
      ArtifactReleaseState releaseState) {
    final InstrumentRef instrument = new InstrumentRef(companies.venueMic(), symbol);
    if (!isRegularBoardCommonStock(symbol)) {
      return unsupportedInstrument(instrument);
    }
    final PriceFacts priceFacts = prices.prices().get(symbol);
    if (priceFacts == null) {
      return unsupportedInstrument(instrument, "NO_CURRENT_PRICE_FACTS");
    }
    if (!priceFacts.hasUsablePriceBand()) {
      return unsupportedInstrument(instrument, "NO_TRADABLE_PRICE_BAND");
    }
    return eligibleInstrument(instrument, priceFacts, releaseState);
  }

  private boolean isRegularBoardCommonStock(String symbol) {
    return symbol.matches("[0-8][0-9]{3}");
  }

  private ArtifactInstrument unsupportedInstrument(InstrumentRef instrument) {
    final String reason;
    if (instrument.symbol().startsWith("9")) {
      reason = "TDR";
    } else if (!instrument.symbol().matches("[0-9]{4}")) {
      reason = "NON_REGULAR_SYMBOL";
    } else {
      reason = "UNSUPPORTED_SECURITY_CLASS";
    }
    return unsupportedInstrument(instrument, reason);
  }

  private ArtifactInstrument unsupportedInstrument(InstrumentRef instrument, String reason) {
    return new ArtifactInstrument(
        instrument, InstrumentEligibility.UNSUPPORTED, reason, null, null, null, null);
  }

  private ArtifactInstrument eligibleInstrument(
      InstrumentRef instrument, PriceFacts priceFacts, ArtifactReleaseState releaseState) {
    final boolean finalRelease = releaseState == ArtifactReleaseState.FINAL;
    return new ArtifactInstrument(
        instrument,
        InstrumentEligibility.ELIGIBLE,
        null,
        PhaseOneMarketRules.REGULAR_BOARD_COMMON_STOCK,
        finalRelease ? priceFacts.referencePriceUnits() : null,
        finalRelease ? priceFacts.lowerPriceLimitUnits() : null,
        finalRelease ? priceFacts.upperPriceLimitUnits() : null);
  }

  private List<SourceProvenance> sourceProvenance(
      OfficialMarketDataSources sources, ParsedOfficialData parsed, LocalDate tradingDay) {
    return List.of(
        provenance(
            sources.document(OfficialSourceType.TWSE_COMPANIES),
            parsed.twseCompanies().sourceDate()),
        provenance(
            sources.document(OfficialSourceType.TPEX_COMPANIES),
            parsed.tpexCompanies().sourceDate()),
        provenance(
            sources.document(OfficialSourceType.TWSE_DAILY_LIMITS),
            tradingDay),
        provenance(
            sources.document(OfficialSourceType.TPEX_DAILY_LIMITS),
            parsed.tpexPrices().uniformDocumentDate()),
        provenance(sources.document(OfficialSourceType.TWSE_TRADING_CALENDAR), tradingDay));
  }

  private SourceProvenance provenance(RetrievedOfficialSource source, LocalDate sourceDate) {
    return new SourceProvenance(
        source.sourceType().sourceId(),
        source.sourceUrl().toString(),
        sourceDate,
        source.retrievedAt().toEpochMilli(),
        source.contentSha256());
  }

  private record ParsedOfficialData(
      OfficialSourceParser.CompanySource twseCompanies,
      OfficialSourceParser.CompanySource tpexCompanies,
      OfficialSourceParser.PriceSource twsePrices,
      OfficialSourceParser.PriceSource tpexPrices,
      OfficialTradingCalendar calendar) {}
}

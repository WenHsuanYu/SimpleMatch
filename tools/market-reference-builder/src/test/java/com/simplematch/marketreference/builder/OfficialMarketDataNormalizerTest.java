package com.simplematch.marketreference.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.ArtifactInstrument;
import com.simplematch.marketreference.ArtifactReleaseState;
import com.simplematch.marketreference.InstrumentEligibility;
import com.simplematch.marketreference.InstrumentRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OfficialMarketDataNormalizerTest {
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 8, 11);
  private static final Instant RETRIEVED_AT = Instant.parse("2026-08-11T00:20:00Z");

  private final OfficialMarketDataNormalizer normalizer =
      new OfficialMarketDataNormalizer(new ObjectMapper());

  @DisplayName("normalizes official TWSE and TPEx facts into eligible and unsupported instruments")
  @Test
  void normalizesOfficialSourcesWithCompleteEligibleFacts() throws IOException {
    final NormalizedOfficialMarketData normalized =
        normalizer.normalize(fixtureSources(), TRADING_DAY, ArtifactReleaseState.FINAL);

    assertThat(instrument(normalized, "XTAI", "2330"))
        .extracting(
            ArtifactInstrument::eligibility,
            ArtifactInstrument::marketRuleId,
            ArtifactInstrument::referencePriceUnits,
            ArtifactInstrument::lowerPriceLimitUnits,
            ArtifactInstrument::upperPriceLimitUnits)
        .containsExactly(
            InstrumentEligibility.ELIGIBLE,
            "regular-board-common-stock",
            23_700_000L,
            21_350_000L,
            26_050_000L);
    assertThat(instrument(normalized, "ROCO", "6488"))
        .extracting(ArtifactInstrument::eligibility, ArtifactInstrument::referencePriceUnits)
        .containsExactly(InstrumentEligibility.ELIGIBLE, 8_540_000L);
    assertThat(normalized.instruments())
        .extracting(item -> item.instrument())
        .doesNotContain(new InstrumentRef("XTAI", "0050"));
    assertThat(normalized.sourceProvenance()).hasSize(OfficialSourceType.values().length);
  }

  @DisplayName("identical fixture bytes and retrieval facts produce identical normalized data")
  @Test
  void normalizesDeterministically() throws IOException {
    final OfficialMarketDataSources sources = fixtureSources();

    assertThat(normalizer.normalize(sources, TRADING_DAY, ArtifactReleaseState.FINAL))
        .isEqualTo(normalizer.normalize(sources, TRADING_DAY, ArtifactReleaseState.FINAL));
  }

  @DisplayName("a stale daily reference source fails closed before an artifact is built")
  @Test
  void rejectsDailySourceForTheWrongPriorTradingDay() throws IOException {
    final Map<OfficialSourceType, RetrievedOfficialSource> documents =
        new EnumMap<>(fixtureSources().documents());
    final RetrievedOfficialSource valid = documents.get(OfficialSourceType.TPEX_DAILY_LIMITS);
    final String stale =
        new String(valid.content(), StandardCharsets.UTF_8).replace("1150810", "1150807");
    documents.put(
        OfficialSourceType.TPEX_DAILY_LIMITS,
        new RetrievedOfficialSource(
            valid.sourceType(), valid.sourceUrl(), valid.retrievedAt(), stale.getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(
            () ->
                normalizer.normalize(
                    new OfficialMarketDataSources(documents),
                    TRADING_DAY,
                    ArtifactReleaseState.FINAL))
        .isInstanceOf(MarketReferenceBuildException.class)
        .hasMessageContaining("prior trading day");
  }

  @DisplayName("accepts differing historical TWSE last-trading days for otherwise valid rows")
  @Test
  void acceptsPerInstrumentTwseLastTradingDays() throws IOException {
    final Map<OfficialSourceType, RetrievedOfficialSource> documents =
        new EnumMap<>(fixtureSources().documents());
    final RetrievedOfficialSource valid = documents.get(OfficialSourceType.TWSE_DAILY_LIMITS);
    final String variedLastTradingDays =
        new String(valid.content(), StandardCharsets.UTF_8)
            .replaceFirst("\\\"LastTradingDay\\\": \\\"1150810\\\"", "\\\"LastTradingDay\\\": \\\"1150807\\\"");
    documents.put(
        OfficialSourceType.TWSE_DAILY_LIMITS,
        new RetrievedOfficialSource(
            valid.sourceType(),
            valid.sourceUrl(),
            valid.retrievedAt(),
            variedLastTradingDays.getBytes(StandardCharsets.UTF_8)));

    assertThat(
            normalizer.normalize(
                new OfficialMarketDataSources(documents),
                TRADING_DAY,
                ArtifactReleaseState.FINAL))
        .isNotNull();
  }

  @DisplayName("a future TWSE last-trading day fails closed")
  @Test
  void rejectsFutureTwseLastTradingDay() throws IOException {
    final Map<OfficialSourceType, RetrievedOfficialSource> documents =
        new EnumMap<>(fixtureSources().documents());
    final RetrievedOfficialSource valid = documents.get(OfficialSourceType.TWSE_DAILY_LIMITS);
    final String futureLastTradingDay =
        new String(valid.content(), StandardCharsets.UTF_8)
            .replaceFirst("\\\"LastTradingDay\\\": \\\"1150810\\\"", "\\\"LastTradingDay\\\": \\\"1150811\\\"");
    documents.put(
        OfficialSourceType.TWSE_DAILY_LIMITS,
        new RetrievedOfficialSource(
            valid.sourceType(),
            valid.sourceUrl(),
            valid.retrievedAt(),
            futureLastTradingDay.getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(
            () ->
                normalizer.normalize(
                    new OfficialMarketDataSources(documents),
                    TRADING_DAY,
                    ArtifactReleaseState.FINAL))
        .isInstanceOf(MarketReferenceBuildException.class)
        .hasMessageContaining("future last-trading-day");
  }

  @DisplayName("a regular company without a usable final price band is explicitly unsupported")
  @Test
  void marksRegularCompanyWithoutUsablePriceBandUnsupported() throws IOException {
    final Map<OfficialSourceType, RetrievedOfficialSource> documents =
        new EnumMap<>(fixtureSources().documents());
    final RetrievedOfficialSource valid = documents.get(OfficialSourceType.TWSE_DAILY_LIMITS);
    final String noTradablePriceBand =
        new String(valid.content(), StandardCharsets.UTF_8)
            .replace("\"TodayLimitDown\": \"2135.00\"", "\"TodayLimitDown\": \"2370.00\"");
    documents.put(
        OfficialSourceType.TWSE_DAILY_LIMITS,
        new RetrievedOfficialSource(
            valid.sourceType(),
            valid.sourceUrl(),
            valid.retrievedAt(),
            noTradablePriceBand.getBytes(StandardCharsets.UTF_8)));

    final NormalizedOfficialMarketData normalized =
        normalizer.normalize(
            new OfficialMarketDataSources(documents), TRADING_DAY, ArtifactReleaseState.FINAL);

    assertThat(instrument(normalized, "XTAI", "2330"))
        .extracting(ArtifactInstrument::eligibility, ArtifactInstrument::ineligibilityReason)
        .containsExactly(InstrumentEligibility.UNSUPPORTED, "NO_TRADABLE_PRICE_BAND");
  }

  @DisplayName("a regular company absent from daily price facts is explicitly unsupported")
  @Test
  void marksRegularCompanyWithoutCurrentPriceFactsUnsupported() throws IOException {
    final Map<OfficialSourceType, RetrievedOfficialSource> documents =
        new EnumMap<>(fixtureSources().documents());
    final RetrievedOfficialSource valid = documents.get(OfficialSourceType.TWSE_DAILY_LIMITS);
    final String noCurrentPriceFacts =
        new String(valid.content(), StandardCharsets.UTF_8)
            .replace("\"Code\": \"2330\"", "\"Code\": \"0051\"");
    documents.put(
        OfficialSourceType.TWSE_DAILY_LIMITS,
        new RetrievedOfficialSource(
            valid.sourceType(),
            valid.sourceUrl(),
            valid.retrievedAt(),
            noCurrentPriceFacts.getBytes(StandardCharsets.UTF_8)));

    final NormalizedOfficialMarketData normalized =
        normalizer.normalize(
            new OfficialMarketDataSources(documents), TRADING_DAY, ArtifactReleaseState.FINAL);

    assertThat(instrument(normalized, "XTAI", "2330"))
        .extracting(ArtifactInstrument::eligibility, ArtifactInstrument::ineligibilityReason)
        .containsExactly(InstrumentEligibility.UNSUPPORTED, "NO_CURRENT_PRICE_FACTS");
  }

  @DisplayName("a final build rejects a TWSE source retrieved on another trading day")
  @Test
  void rejectsFinalTwseSourceRetrievedOnAnotherDay() throws IOException {
    final Map<OfficialSourceType, RetrievedOfficialSource> documents =
        new EnumMap<>(fixtureSources().documents());
    final RetrievedOfficialSource valid = documents.get(OfficialSourceType.TWSE_DAILY_LIMITS);
    documents.put(
        OfficialSourceType.TWSE_DAILY_LIMITS,
        new RetrievedOfficialSource(
            valid.sourceType(),
            valid.sourceUrl(),
            Instant.parse("2026-08-12T00:20:00Z"),
            valid.content()));

    assertThatThrownBy(
            () ->
                normalizer.normalize(
                    new OfficialMarketDataSources(documents),
                    TRADING_DAY,
                    ArtifactReleaseState.FINAL))
        .isInstanceOf(MarketReferenceBuildException.class)
        .hasMessageContaining("retrieved on the target trading day");
  }

  @DisplayName("a duplicated official instrument row fails closed")
  @Test
  void rejectsDuplicateOfficialPriceRows() throws IOException {
    final Map<OfficialSourceType, RetrievedOfficialSource> documents =
        new EnumMap<>(fixtureSources().documents());
    final RetrievedOfficialSource valid = documents.get(OfficialSourceType.TPEX_DAILY_LIMITS);
    final String source = new String(valid.content(), StandardCharsets.UTF_8);
    final String duplicated = source.replace("006201", "6488");
    documents.put(
        OfficialSourceType.TPEX_DAILY_LIMITS,
        new RetrievedOfficialSource(
            valid.sourceType(),
            valid.sourceUrl(),
            valid.retrievedAt(),
            duplicated.getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(
            () ->
                normalizer.normalize(
                    new OfficialMarketDataSources(documents),
                    TRADING_DAY,
                    ArtifactReleaseState.FINAL))
        .isInstanceOf(MarketReferenceBuildException.class)
        .hasMessageContaining("duplicate price instrument");
  }

  private ArtifactInstrument instrument(
      NormalizedOfficialMarketData normalized, String venueMic, String symbol) {
    return normalized.instruments().stream()
        .filter(item -> item.instrument().equals(new InstrumentRef(venueMic, symbol)))
        .findFirst()
        .orElseThrow();
  }

  private OfficialMarketDataSources fixtureSources() throws IOException {
    final Map<OfficialSourceType, RetrievedOfficialSource> documents =
        new EnumMap<>(OfficialSourceType.class);
    for (OfficialSourceType sourceType : OfficialSourceType.values()) {
      documents.put(
          sourceType,
          new RetrievedOfficialSource(
              sourceType,
              sourceType.endpoint(),
              RETRIEVED_AT,
              getClass()
                  .getResourceAsStream("/official-sources/" + sourceType.fixtureFileName())
                  .readAllBytes()));
    }
    return new OfficialMarketDataSources(documents);
  }
}

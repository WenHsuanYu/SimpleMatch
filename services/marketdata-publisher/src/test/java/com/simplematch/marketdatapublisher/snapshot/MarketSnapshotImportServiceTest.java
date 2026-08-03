package com.simplematch.marketdatapublisher.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketSnapshotImportServiceTest {
  private final MarketSnapshotImportService importService =
      new MarketSnapshotImportService(new ObjectMapper());

  @DisplayName("XTAI and ROCO source fixtures create a deterministic immutable snapshot")
  @Test
  void preparesDeterministicSnapshotForSupportedVenues() throws IOException {
    final byte[] fixture = fixture("fixtures/xtai-and-roco-snapshot.json");

    final PreparedMarketSnapshot first = importService.prepare(fixture);
    final PreparedMarketSnapshot second = importService.prepare(fixture);

    assertThat(first).isEqualTo(second);
    assertThat(first.tradingDay()).isEqualTo(LocalDate.of(2026, 7, 27));
    assertThat(first.checksum()).hasSize(64);
    assertThat(first.instruments())
        .extracting(MarketInstrument::venueMic)
        .containsExactlyInAnyOrder("ROCO", "XTAI", "XTAI");
    assertThat(first.instruments())
        .filteredOn(MarketInstrument::eligible)
        .extracting(MarketInstrument::symbol)
        .containsExactlyInAnyOrder("2330", "6488");
  }

  @DisplayName(
      "unsupported instruments remain in the immutable snapshot with a stable eligibility reason")
  @Test
  void preservesUnsupportedInstrumentEligibilityReason() throws IOException {
    final PreparedMarketSnapshot snapshot =
        importService.prepare(fixture("fixtures/xtai-and-roco-snapshot.json"));

    final MarketInstrument unsupported =
        snapshot.instruments().stream()
            .filter(instrument -> instrument.symbol().equals("0050"))
            .findFirst()
            .orElseThrow();

    assertThat(unsupported.eligible()).isFalse();
    assertThat(unsupported.eligibilityReason())
        .isEqualTo(EligibilityReason.UNSUPPORTED_SECURITY_TYPE);
  }

  @DisplayName("normalized instruments expose semantic identity, rules, and price-band values")
  @Test
  void exposesSemanticInstrumentValues() throws IOException {
    final PreparedMarketSnapshot snapshot =
        importService.prepare(fixture("fixtures/xtai-and-roco-snapshot.json"));

    final MarketInstrument instrument =
        snapshot.instruments().stream()
            .filter(candidate -> candidate.symbol().equals("2330"))
            .findFirst()
            .orElseThrow();

    assertThat(instrument.identity())
        .isEqualTo(new InstrumentIdentity("2330", "XTAI"));
    assertThat(instrument.tradingRules().boardLotShares()).isEqualTo(1000);
    assertThat(instrument.tradingRules().referencePriceBand())
        .isEqualTo(new ReferencePriceBand(10_000_000, 9_000_000, 11_000_000));
  }

  @DisplayName("canonical content retains the flat normalized instrument shape")
  @Test
  void retainsFlatCanonicalInstrumentContent() throws IOException {
    final PreparedMarketSnapshot snapshot =
        importService.prepare(fixture("fixtures/xtai-and-roco-snapshot.json"));

    final var instrument =
        new ObjectMapper().readTree(snapshot.canonicalContent()).path("instruments").get(0);

    assertThat(instrument.has("symbol")).isTrue();
    assertThat(instrument.has("venueMic")).isTrue();
    assertThat(instrument.has("boardLotShares")).isTrue();
    assertThat(instrument.has("referencePriceUnits")).isTrue();
    assertThat(instrument.has("identity")).isFalse();
    assertThat(instrument.has("tradingRules")).isFalse();
  }

  @DisplayName("unsupported venues remain present with explicit ineligibility")
  @Test
  void preservesUnsupportedVenueAsIneligibleInstrument() {
    final String source =
        sourceForTradingDay("2026-07-27").replace("\"venueMic\": \"XTAI\"", "\"venueMic\": \"UNKNOWN\"");

    final MarketInstrument instrument =
        importService
            .prepare(source.getBytes(StandardCharsets.UTF_8))
            .instruments()
            .getFirst();

    assertThat(instrument.venueMic()).isEqualTo("UNKNOWN");
    assertThat(instrument.eligible()).isFalse();
    assertThat(instrument.eligibilityReason()).isEqualTo(EligibilityReason.UNSUPPORTED_VENUE);
  }

  @DisplayName("malformed board lots and tick sizes still reject source import")
  @Test
  void rejectsMalformedTradingRules() {
    final String invalidBoardLot =
        sourceForTradingDay("2026-07-27").replace("\"boardLotShares\": 1000", "\"boardLotShares\": 0");
    final String invalidTickSize =
        sourceForTradingDay("2026-07-27").replace("\"tickSize\": \"0.01\"", "\"tickSize\": \"0\"");

    assertThatThrownBy(() -> importService.prepare(invalidBoardLot.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(MarketSnapshotValidationException.class)
        .hasMessageContaining("board lot must be positive");
    assertThatThrownBy(() -> importService.prepare(invalidTickSize.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(MarketSnapshotValidationException.class)
        .hasMessageContaining("tick size must be a positive TWD price");
  }

  @DisplayName("holidays and weekends cannot be published as Taiwan trading days")
  @Test
  void rejectsHolidayAndWeekendTradingDays() {
    final String holidaySource = sourceForTradingDay("2026-10-09");
    final String weekendSource = sourceForTradingDay("2026-07-26");

    assertThatThrownBy(() -> importService.prepare(holidaySource.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(MarketSnapshotValidationException.class)
        .hasMessageContaining("not a Taiwan trading day");
    assertThatThrownBy(() -> importService.prepare(weekendSource.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(MarketSnapshotValidationException.class)
        .hasMessageContaining("not a Taiwan trading day");
  }

  @DisplayName("price limits must bracket the reference price and align to the tick table")
  @Test
  void rejectsInvalidAbsolutePriceLimits() {
    final String invalidLimits =
        sourceForTradingDay("2026-07-27")
            .replace("\"lowerPriceLimit\": \"900\"", "\"lowerPriceLimit\": \"1000\"");

    assertThatThrownBy(() -> importService.prepare(invalidLimits.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(MarketSnapshotValidationException.class)
        .hasMessageContaining("must bracket the reference price");
  }

  @DisplayName("a source cannot define the same venue instrument identity twice")
  @Test
  void rejectsDuplicateVenueInstrumentIdentity() throws IOException {
    final String duplicateIdentity =
        new String(fixture("fixtures/xtai-and-roco-snapshot.json"), StandardCharsets.UTF_8)
            .replace("\"symbol\": \"0050\"", "\"symbol\": \"2330\"");

    assertThatThrownBy(
            () -> importService.prepare(duplicateIdentity.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(MarketSnapshotValidationException.class)
        .hasMessageContaining("duplicate venue instrument identity");
  }

  private byte[] fixture(String resourceName) throws IOException {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourceName)) {
      return input.readAllBytes();
    }
  }

  private String sourceForTradingDay(String tradingDay) {
    return """
                {
                  "sourceIdentity": "test-source",
                  "sourceTimestampUnixMs": 1785110400000,
                  "tradingDay": "%s",
                  "holidays": ["2026-10-09"],
                  "instruments": [{
                    "symbol": "2330", "venueMic": "XTAI", "securityType": "COMMON_STOCK",
                    "boardLotShares": 1000, "referencePrice": "1000", "lowerPriceLimit": "900",
                    "upperPriceLimit": "1100", "tickBands": [{"upperExclusive": "10", "tickSize": "0.01"}, {"tickSize": "0.5"}]
                  }]
                }
                """
        .formatted(tradingDay);
  }
}

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
    assertThat(first.instruments()).extracting(MarketInstrument::venueMic)
        .containsExactlyInAnyOrder("ROCO", "XTAI", "XTAI");
    assertThat(first.instruments()).filteredOn(MarketInstrument::eligible)
        .extracting(MarketInstrument::symbol)
        .containsExactlyInAnyOrder("2330", "6488");
  }

  @DisplayName("unsupported instruments remain in the immutable snapshot with a stable eligibility reason")
  @Test
  void preservesUnsupportedInstrumentEligibilityReason() throws IOException {
    final PreparedMarketSnapshot snapshot = importService.prepare(fixture("fixtures/xtai-and-roco-snapshot.json"));

    final MarketInstrument unsupported = snapshot.instruments().stream()
        .filter(instrument -> instrument.symbol().equals("0050"))
        .findFirst()
        .orElseThrow();

    assertThat(unsupported.eligible()).isFalse();
    assertThat(unsupported.eligibilityReason()).isEqualTo(EligibilityReason.UNSUPPORTED_SECURITY_TYPE);
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
    final String invalidLimits = sourceForTradingDay("2026-07-27")
        .replace("\"lowerPriceLimit\": \"900\"", "\"lowerPriceLimit\": \"1000\"");

    assertThatThrownBy(() -> importService.prepare(invalidLimits.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(MarketSnapshotValidationException.class)
        .hasMessageContaining("must bracket the reference price");
  }

  @DisplayName("a source cannot define the same venue instrument identity twice")
  @Test
  void rejectsDuplicateVenueInstrumentIdentity() throws IOException {
    final String duplicateIdentity = new String(fixture("fixtures/xtai-and-roco-snapshot.json"), StandardCharsets.UTF_8)
        .replace("\"symbol\": \"0050\"", "\"symbol\": \"2330\"");

    assertThatThrownBy(() -> importService.prepare(duplicateIdentity.getBytes(StandardCharsets.UTF_8)))
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
        """.formatted(tradingDay);
  }
}

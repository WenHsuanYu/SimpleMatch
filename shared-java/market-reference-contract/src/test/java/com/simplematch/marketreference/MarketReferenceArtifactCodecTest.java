package com.simplematch.marketreference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketReferenceArtifactCodecTest {
  private final MarketReferenceArtifactCodec codec =
      new MarketReferenceArtifactCodec(new ObjectMapper());

  @DisplayName("canonical artifact bytes are deterministic and have an external identity")
  @Test
  void writesDeterministicBytesAndVerifiesExternalChecksumBeforeParsing() {
    final MarketReferenceArtifact artifact = artifact();

    final byte[] first = codec.write(artifact);
    final byte[] second = codec.write(artifact);
    final String checksum = ArtifactChecksum.sha256(first);

    assertThat(first).isEqualTo(second);
    assertThat(checksum).hasSize(64);
    assertThat(ArtifactIdentity.of(artifact.metadata().tradingDay(), checksum).value())
        .isEqualTo("2026-08-11:" + checksum);
    assertThat(codec.readVerified(first, checksum, LocalDate.of(2026, 8, 11))).isEqualTo(artifact);
    assertThat(new String(first, StandardCharsets.UTF_8)).contains("\"metadata\"");
  }

  @DisplayName("a mismatched external checksum fails before malformed JSON is parsed")
  @Test
  void rejectsMismatchedExternalChecksumBeforeParsing() {
    final byte[] malformed = "not json".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(
            () ->
                codec.readVerified(
                    malformed,
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    LocalDate.of(2026, 8, 11)))
        .isInstanceOf(MarketReferenceValidationException.class)
        .hasMessageContaining("checksum");
  }

  private MarketReferenceArtifact artifact() {
    final InstrumentRef eligible = new InstrumentRef("XTAI", "2330");
    final InstrumentRef unsupported = new InstrumentRef("XTAI", "0050");
    return new MarketReferenceArtifact(
        new ArtifactMetadata(
            1,
            ArtifactReleaseState.FINAL,
            LocalDate.of(2026, 8, 11),
            "stable-least-loaded-v1",
            List.of(
                new SourceProvenance(
                    "twse-price-limits",
                    "https://openapi.twse.com.tw/v1/exchangeReport/TWT84U",
                    LocalDate.of(2026, 8, 11),
                    1_786_382_400_000L,
                    "a".repeat(64)))),
        new MarketRules(
            "phase1-tw-cash-v1",
            "TWD",
            List.of(
                new MarketRule(
                    "regular-board-common-stock",
                    1_000,
                    "twd-standard-v1")),
            List.of(
                new TickTableDefinition(
                    "twd-standard-v1",
                    List.of(
                        new TickBandDefinition(100_000L, 100L),
                        new TickBandDefinition(null, 5_000L))))),
        new MarketSnapshot(
            List.of(
                new ArtifactInstrument(
                    eligible,
                    InstrumentEligibility.ELIGIBLE,
                    null,
                    "regular-board-common-stock",
                    10_000_000L,
                    9_000_000L,
                    11_000_000L),
                new ArtifactInstrument(
                    unsupported,
                    InstrumentEligibility.UNSUPPORTED,
                    "NOT_LISTED_COMMON_STOCK",
                    null,
                    null,
                    null,
                    null))),
        new RoutingPolicy(
            "stable-least-loaded-v1",
            15,
            150,
            List.of(new RoutingAssignment(eligible, 0))));
  }
}

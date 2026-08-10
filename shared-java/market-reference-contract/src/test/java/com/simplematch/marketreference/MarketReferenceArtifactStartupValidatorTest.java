package com.simplematch.marketreference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketReferenceArtifactStartupValidatorTest {
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 8, 11);
  private final MarketReferenceArtifactStartupValidator validator =
      new MarketReferenceArtifactStartupValidator(new ObjectMapper());

  @DisplayName("the reusable startup validator reads the shared final artifact fixture")
  @Test
  void readsTheSharedFinalArtifactFixture() throws IOException {
    final byte[] artifact = resource("/market-reference-fixtures/market_reference.json");
    final String checksum =
        new String(
                resource("/market-reference-fixtures/market_reference.sha256"),
                StandardCharsets.US_ASCII)
            .trim();

    final VerifiedMarketReferenceArtifact verified =
        validator.validate(artifact, checksum, TRADING_DAY);

    assertThat(verified.identity().value()).isEqualTo(TRADING_DAY + ":" + checksum);
    assertThat(verified.artifact().routingPolicy().assignments()).hasSize(1);
  }

  @DisplayName("a startup checksum mismatch fails before parsing the fixture bytes")
  @Test
  void rejectsChecksumMismatchBeforeArtifactParsing() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    "not json".getBytes(StandardCharsets.UTF_8), "a".repeat(64), TRADING_DAY))
        .isInstanceOf(MarketReferenceValidationException.class)
        .hasMessageContaining("checksum");
  }

  private byte[] resource(String path) throws IOException {
    return getClass().getResourceAsStream(path).readAllBytes();
  }
}

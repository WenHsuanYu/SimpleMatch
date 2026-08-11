package com.simplematch.riskservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** Tests the single startup read that turns mounted artifact files into the Risk routing authority. */
class DailyMarketReferenceArtifactLoaderTest {
  private static final String ARTIFACT = "classpath:/market-reference/market_reference.json";
  private static final String CHECKSUM = "classpath:/market-reference/market_reference.sha256";
  private static final String MATCHING_IMAGE =
      "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  @DisplayName("loads one final artifact and exposes its immutable identity")
  @Test
  void loadsFinalArtifactOnce() {
    final DailyMarketReferenceArtifactLoader loader =
        new DailyMarketReferenceArtifactLoader(new DefaultResourceLoader(), new ObjectMapper());

    final var verified =
        loader.load(
            new MarketReferenceArtifactProperties(
                ARTIFACT, CHECKSUM, LocalDate.of(2026, 8, 11), MATCHING_IMAGE));

    assertThat(verified.identity().tradingDay()).isEqualTo(LocalDate.of(2026, 8, 11));
    assertThat(verified.artifact().routingPolicy().partitionCount()).isEqualTo(15);
  }

  @DisplayName("does not start when a mounted artifact location is missing")
  @Test
  void failsClosedForMissingArtifact() {
    final DailyMarketReferenceArtifactLoader loader =
        new DailyMarketReferenceArtifactLoader(new DefaultResourceLoader(), new ObjectMapper());

    assertThatThrownBy(
            () ->
                loader.load(
                    new MarketReferenceArtifactProperties(
                        "classpath:/missing.json",
                        CHECKSUM,
                        LocalDate.of(2026, 8, 11),
                        MATCHING_IMAGE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("market reference artifact");
  }
}

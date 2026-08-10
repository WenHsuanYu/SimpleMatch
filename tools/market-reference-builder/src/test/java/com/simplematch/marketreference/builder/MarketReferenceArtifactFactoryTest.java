package com.simplematch.marketreference.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.marketreference.ArtifactInstrument;
import com.simplematch.marketreference.ArtifactReleaseState;
import com.simplematch.marketreference.InstrumentEligibility;
import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.marketreference.SourceProvenance;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketReferenceArtifactFactoryTest {
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 8, 11);
  private final MarketReferenceArtifactFactory factory = new MarketReferenceArtifactFactory();

  @DisplayName("a preliminary candidate has stable routes but no deployable price facts")
  @Test
  void buildsPreliminaryCandidateWithoutDailyPriceFacts() {
    final ArtifactBuildResult result =
        factory.build(candidateData(), TRADING_DAY, ArtifactReleaseState.PRELIMINARY, null);

    assertThat(result.artifact().metadata().releaseState())
        .isEqualTo(ArtifactReleaseState.PRELIMINARY);
    assertThat(result.artifact().marketSnapshot().instruments())
        .filteredOn(item -> item.eligibility() == InstrumentEligibility.ELIGIBLE)
        .extracting(ArtifactInstrument::referencePriceUnits)
        .containsOnlyNulls();
    assertThat(result.artifact().routingPolicy().assignments()).hasSize(2);
  }

  @DisplayName("a final artifact keeps prior routes and includes official price facts")
  @Test
  void buildsFinalArtifactWithRetainedRoutesAndPrices() {
    final ArtifactBuildResult candidate =
        factory.build(candidateData(), TRADING_DAY, ArtifactReleaseState.PRELIMINARY, null);
    final ArtifactBuildResult finalResult =
        factory.build(finalData(), TRADING_DAY, ArtifactReleaseState.FINAL, candidate.artifact().routingPolicy());

    assertThat(finalResult.artifact().marketSnapshot().instruments())
        .filteredOn(item -> item.eligibility() == InstrumentEligibility.ELIGIBLE)
        .extracting(ArtifactInstrument::referencePriceUnits)
        .doesNotContainNull();
    assertThat(finalResult.routingAllocation().routeChanges())
        .allSatisfy(change -> assertThat(change.previousPartitionId()).isEqualTo(change.partitionId()));
  }

  private NormalizedOfficialMarketData candidateData() {
    return new NormalizedOfficialMarketData(
        PhaseOneMarketRules.marketRules(),
        List.of(candidateEligible("XTAI", "2330"), candidateEligible("ROCO", "6488")),
        provenance());
  }

  private NormalizedOfficialMarketData finalData() {
    return new NormalizedOfficialMarketData(
        PhaseOneMarketRules.marketRules(),
        List.of(finalEligible("XTAI", "2330"), finalEligible("ROCO", "6488")),
        provenance());
  }

  private ArtifactInstrument candidateEligible(String venueMic, String symbol) {
    return new ArtifactInstrument(
        new InstrumentRef(venueMic, symbol),
        InstrumentEligibility.ELIGIBLE,
        null,
        PhaseOneMarketRules.REGULAR_BOARD_COMMON_STOCK,
        null,
        null,
        null);
  }

  private ArtifactInstrument finalEligible(String venueMic, String symbol) {
    return new ArtifactInstrument(
        new InstrumentRef(venueMic, symbol),
        InstrumentEligibility.ELIGIBLE,
        null,
        PhaseOneMarketRules.REGULAR_BOARD_COMMON_STOCK,
        1_000_000L,
        900_000L,
        1_100_000L);
  }

  private List<SourceProvenance> provenance() {
    return List.of(
        new SourceProvenance(
            "fixture",
            "https://example.test/fixture",
            TRADING_DAY.minusDays(1),
            1_786_406_400_000L,
            "a".repeat(64)));
  }
}

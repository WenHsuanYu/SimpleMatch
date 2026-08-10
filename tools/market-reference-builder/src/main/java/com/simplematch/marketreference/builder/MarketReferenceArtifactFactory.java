package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.ArtifactMetadata;
import com.simplematch.marketreference.ArtifactReleaseState;
import com.simplematch.marketreference.MarketReferenceArtifact;
import com.simplematch.marketreference.MarketReferenceArtifactValidator;
import com.simplematch.marketreference.RoutingAllocationResult;
import com.simplematch.marketreference.RoutingPolicy;
import com.simplematch.marketreference.StableRoutingAllocator;
import java.time.LocalDate;
import java.util.Objects;

/** Constructs validated candidate and final artifacts from normalized daily source data. */
public final class MarketReferenceArtifactFactory {
  private final StableRoutingAllocator routingAllocator;
  private final MarketReferenceArtifactValidator validator;

  /** Creates a factory for the fixed Phase 1 artifact topology. */
  public MarketReferenceArtifactFactory() {
    this.routingAllocator = new StableRoutingAllocator();
    this.validator = new MarketReferenceArtifactValidator();
  }

  /**
   * Builds a candidate or final artifact, retaining routes from an approved prior policy.
   *
   * @param data normalized official market data
   * @param tradingDay requested Asia/Taipei trading day
   * @param releaseState preliminary or final state
   * @param previousPolicy approved prior routing policy, or {@code null} for the baseline
   * @return validated artifact and bounded routing diagnostics
   */
  public ArtifactBuildResult build(
      NormalizedOfficialMarketData data,
      LocalDate tradingDay,
      ArtifactReleaseState releaseState,
      RoutingPolicy previousPolicy) {
    Objects.requireNonNull(data, "normalized market data is required");
    Objects.requireNonNull(tradingDay, "trading day is required");
    Objects.requireNonNull(releaseState, "artifact release state is required");
    final RoutingAllocationResult allocation =
        routingAllocator.allocate(data.instruments(), previousPolicy);
    final MarketReferenceArtifact artifact =
        new MarketReferenceArtifact(
            new ArtifactMetadata(
                1,
                releaseState,
                tradingDay,
                allocation.routingPolicy().algorithmVersion(),
                data.sourceProvenance()),
            data.marketRules(),
            new com.simplematch.marketreference.MarketSnapshot(data.instruments()),
            allocation.routingPolicy());
    validator.validate(artifact);
    return new ArtifactBuildResult(artifact, allocation);
  }
}

package com.simplematch.marketreference.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.ArtifactChecksum;
import com.simplematch.marketreference.ArtifactIdentity;
import com.simplematch.marketreference.ArtifactReleaseState;
import com.simplematch.marketreference.MarketReferenceArtifact;
import com.simplematch.marketreference.MarketReferenceArtifactCodec;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/** Runs the explicit D-1 candidate and trading-day final Market Reference workflow. */
public final class DailyMarketReferenceWorkflow {
  private final OfficialMarketDataNormalizer normalizer;
  private final MarketReferenceArtifactFactory artifactFactory;
  private final MarketReferenceArtifactCodec codec;
  private final ArtifactDeliveryPlanner deliveryPlanner;
  private final ArtifactReviewSummaryFactory reviewSummaryFactory;
  private final Clock clock;

  /** Creates a workflow whose externally visible approval time is supplied by the given clock. */
  public DailyMarketReferenceWorkflow(ObjectMapper objectMapper, Clock clock) {
    final ObjectMapper mapper = Objects.requireNonNull(objectMapper, "object mapper is required");
    this.normalizer = new OfficialMarketDataNormalizer(mapper);
    this.artifactFactory = new MarketReferenceArtifactFactory();
    this.codec = new MarketReferenceArtifactCodec(mapper);
    this.deliveryPlanner = new ArtifactDeliveryPlanner();
    this.reviewSummaryFactory = new ArtifactReviewSummaryFactory();
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  /**
   * Builds the D-1 non-deployable universe and routing candidate.
   *
   * @param sources newly retrieved official source documents
   * @param tradingDay target Asia/Taipei trading day
   * @param previousArtifact prior approved artifact, or {@code null} for the baseline
   * @return preliminary candidate that cannot produce a delivery plan
   */
  public CandidateArtifact createCandidate(
      OfficialMarketDataSources sources,
      LocalDate tradingDay,
      MarketReferenceArtifact previousArtifact) {
    final ArtifactBuildResult result =
        build(sources, tradingDay, ArtifactReleaseState.PRELIMINARY, previousArtifact);
    final byte[] artifactBytes = codec.write(result.artifact());
    final String checksum = ArtifactChecksum.sha256(artifactBytes);
    final ArtifactReviewSummary reviewSummary =
        reviewSummaryFactory.create(
            result.artifact(),
            previousArtifact,
            result.routingAllocation(),
            artifactBytes.length,
            checksum,
            null);
    return new CandidateArtifact(result, artifactBytes, checksum, reviewSummary);
  }

  /**
   * Re-fetches final source facts, verifies exact bytes, and creates an approved delivery artifact.
   *
   * @param sources newly retrieved official source documents for finalization
   * @param tradingDay target Asia/Taipei trading day
   * @param previousArtifact prior approved artifact, or {@code null} for the baseline
   * @param approval explicit operator approval evidence
   * @param ociDataImageReference digest-pinned image required only above the ConfigMap cap
   * @return verified final artifact with an immutable delivery plan
   */
  public FinalArtifact createFinal(
      OfficialMarketDataSources sources,
      LocalDate tradingDay,
      MarketReferenceArtifact previousArtifact,
      OperatorApproval approval,
      String ociDataImageReference) {
    requireCurrentApproval(approval);
    final ArtifactBuildResult result =
        build(sources, tradingDay, ArtifactReleaseState.FINAL, previousArtifact);
    final byte[] artifactBytes = codec.write(result.artifact());
    final String checksum = ArtifactChecksum.sha256(artifactBytes);
    codec.readVerified(artifactBytes, checksum, tradingDay);
    final ArtifactIdentity identity = ArtifactIdentity.of(tradingDay, checksum);
    final ArtifactDeliveryPlan deliveryPlan =
        deliveryPlanner.plan(identity, artifactBytes, checksum, ociDataImageReference);
    final ArtifactReviewSummary reviewSummary =
        reviewSummaryFactory.create(
            result.artifact(),
            previousArtifact,
            result.routingAllocation(),
            artifactBytes.length,
            checksum,
            deliveryPlan);
    final MarketReferenceApprovalReport report =
        new MarketReferenceApprovalReport(
            1,
            identity,
            approval,
            result.artifact().metadata().sourceProvenance(),
            reviewSummary);
    return new FinalArtifact(result, identity, artifactBytes, deliveryPlan, report);
  }

  private ArtifactBuildResult build(
      OfficialMarketDataSources sources,
      LocalDate tradingDay,
      ArtifactReleaseState releaseState,
      MarketReferenceArtifact previousArtifact) {
    final NormalizedOfficialMarketData normalized =
        normalizer.normalize(sources, tradingDay, releaseState);
    return artifactFactory.build(
        normalized,
        tradingDay,
        releaseState,
        previousArtifact == null ? null : previousArtifact.routingPolicy());
  }

  private void requireCurrentApproval(OperatorApproval approval) {
    if (approval == null) {
      throw new MarketReferenceBuildException("final artifact requires operator approval");
    }
    if (approval.approvedAtUnixMs() > clock.millis()) {
      throw new MarketReferenceBuildException("operator approval time cannot be in the future");
    }
  }
}

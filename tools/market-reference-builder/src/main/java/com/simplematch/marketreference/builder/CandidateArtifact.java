package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.MarketReferenceArtifact;
import java.util.Objects;

/** A D-1 review candidate that has no deployment form and may not open trading. */
public final class CandidateArtifact {
  private final ArtifactBuildResult buildResult;
  private final byte[] artifactBytes;
  private final String contentSha256;
  private final ArtifactReviewSummary reviewSummary;

  /** Preserves the non-deployable candidate bytes and its bounded review evidence. */
  public CandidateArtifact(
      ArtifactBuildResult buildResult,
      byte[] artifactBytes,
      String contentSha256,
      ArtifactReviewSummary reviewSummary) {
    this.buildResult = Objects.requireNonNull(buildResult, "candidate build result is required");
    if (artifactBytes == null || artifactBytes.length == 0) {
      throw new MarketReferenceBuildException("candidate artifact bytes are required");
    }
    this.artifactBytes = artifactBytes.clone();
    this.contentSha256 = Objects.requireNonNull(contentSha256, "candidate checksum is required");
    this.reviewSummary =
        Objects.requireNonNull(reviewSummary, "candidate review summary is required");
  }

  /** Returns the preliminary artifact model. */
  public MarketReferenceArtifact artifact() {
    return buildResult.artifact();
  }

  /** Returns an isolated copy of the candidate's canonical bytes. */
  public byte[] artifactBytes() {
    return artifactBytes.clone();
  }

  /** Returns the candidate's review-only content hash. */
  public String contentSha256() {
    return contentSha256;
  }

  /** Returns bounded review evidence for the preliminary candidate. */
  public ArtifactReviewSummary reviewSummary() {
    return reviewSummary;
  }

  /** Candidates have no ConfigMap or OCI deployment plan. */
  public ArtifactDeliveryPlan deliveryPlan() {
    return null;
  }
}

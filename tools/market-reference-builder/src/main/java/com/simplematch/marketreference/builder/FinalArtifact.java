package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.ArtifactIdentity;
import com.simplematch.marketreference.MarketReferenceArtifact;
import java.util.Objects;

/** Fully verified, operator-approved final artifact ready for immutable delivery retention. */
public final class FinalArtifact {
  private final ArtifactBuildResult buildResult;
  private final ArtifactIdentity identity;
  private final byte[] artifactBytes;
  private final ArtifactDeliveryPlan deliveryPlan;
  private final MarketReferenceApprovalReport approvalReport;

  /** Preserves all evidence required to retain and deploy a final artifact safely. */
  public FinalArtifact(
      ArtifactBuildResult buildResult,
      ArtifactIdentity identity,
      byte[] artifactBytes,
      ArtifactDeliveryPlan deliveryPlan,
      MarketReferenceApprovalReport approvalReport) {
    this.buildResult = Objects.requireNonNull(buildResult, "final build result is required");
    this.identity = Objects.requireNonNull(identity, "final artifact identity is required");
    if (artifactBytes == null || artifactBytes.length == 0) {
      throw new MarketReferenceBuildException("final artifact bytes are required");
    }
    this.artifactBytes = artifactBytes.clone();
    this.deliveryPlan = Objects.requireNonNull(deliveryPlan, "final delivery plan is required");
    this.approvalReport = Objects.requireNonNull(approvalReport, "approval report is required");
  }

  /** Returns the final artifact model. */
  public MarketReferenceArtifact artifact() {
    return buildResult.artifact();
  }

  /** Returns the immutable final artifact identity. */
  public ArtifactIdentity identity() {
    return identity;
  }

  /** Returns an isolated copy of the exact canonical final bytes. */
  public byte[] artifactBytes() {
    return artifactBytes.clone();
  }

  /** Returns the selected immutable deployment form. */
  public ArtifactDeliveryPlan deliveryPlan() {
    return deliveryPlan;
  }

  /** Returns the persisted operator approval evidence. */
  public MarketReferenceApprovalReport approvalReport() {
    return approvalReport;
  }
}

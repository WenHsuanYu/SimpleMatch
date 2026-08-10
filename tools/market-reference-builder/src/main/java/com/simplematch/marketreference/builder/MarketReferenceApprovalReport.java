package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.ArtifactIdentity;
import com.simplematch.marketreference.SourceProvenance;
import java.util.List;
import java.util.Objects;

/**
 * Immutable evidence that a final artifact passed source, integrity, review, and delivery gates.
 */
public record MarketReferenceApprovalReport(
    int reportVersion,
    ArtifactIdentity artifactIdentity,
    OperatorApproval approval,
    List<SourceProvenance> sourceProvenance,
    ArtifactReviewSummary reviewSummary) {
  /** Requires current report version and the exact approval evidence for one final artifact. */
  public MarketReferenceApprovalReport {
    if (reportVersion != 1) {
      throw new MarketReferenceBuildException("unsupported approval report version");
    }
    Objects.requireNonNull(artifactIdentity, "approval artifact identity is required");
    Objects.requireNonNull(approval, "operator approval is required");
    sourceProvenance =
        List.copyOf(Objects.requireNonNull(sourceProvenance, "source provenance is required"));
    Objects.requireNonNull(reviewSummary, "approval review summary is required");
  }
}

package com.simplematch.marketreference;

import java.util.Objects;

/** A final artifact paired with the independently verified identity used at startup. */
public record VerifiedMarketReferenceArtifact(
    MarketReferenceArtifact artifact, ArtifactIdentity identity) {
  /** Requires the validated artifact and its exact external checksum identity. */
  public VerifiedMarketReferenceArtifact {
    Objects.requireNonNull(artifact, "verified artifact is required");
    Objects.requireNonNull(identity, "verified artifact identity is required");
  }
}

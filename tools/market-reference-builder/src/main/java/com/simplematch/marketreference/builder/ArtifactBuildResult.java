package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.MarketReferenceArtifact;
import com.simplematch.marketreference.RoutingAllocationResult;
import java.util.Objects;

/** One semantically valid artifact and the routing diagnostics that produced it. */
public record ArtifactBuildResult(
    MarketReferenceArtifact artifact, RoutingAllocationResult routingAllocation) {
  /** Requires both the artifact and its operator-visible allocation diagnostics. */
  public ArtifactBuildResult {
    Objects.requireNonNull(artifact, "artifact is required");
    Objects.requireNonNull(routingAllocation, "routing allocation is required");
  }
}

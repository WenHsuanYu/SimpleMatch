package com.simplematch.marketreference;

import java.util.Objects;

/** The single canonical envelope shared by Risk and all Matching owners. */
public record MarketReferenceArtifact(
    ArtifactMetadata metadata,
    MarketRules marketRules,
    MarketSnapshot marketSnapshot,
    RoutingPolicy routingPolicy) {
  /** Requires all four artifact sections. */
  public MarketReferenceArtifact {
    Objects.requireNonNull(metadata, "artifact metadata is required");
    Objects.requireNonNull(marketRules, "artifact market rules are required");
    Objects.requireNonNull(marketSnapshot, "artifact market snapshot is required");
    Objects.requireNonNull(routingPolicy, "artifact routing policy is required");
  }
}

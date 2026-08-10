package com.simplematch.marketreference.builder;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded operator-review facts that avoid requiring manual inspection of every instrument row. */
public record ArtifactReviewSummary(
    int eligibleInstrumentCount,
    int unsupportedInstrumentCount,
    int additionsTotal,
    List<String> additionsSample,
    int removalsTotal,
    List<String> removalsSample,
    int eligibilityChangesTotal,
    List<String> eligibilityChangesSample,
    int routeChangesTotal,
    List<String> routeChangesSample,
    Map<Integer, Integer> partitionLoads,
    long artifactSizeBytes,
    String contentSha256,
    String deliveryType,
    List<String> validationResults) {
  /** Defensively preserves bounded lists, deterministic loads, and final validation evidence. */
  public ArtifactReviewSummary {
    additionsSample =
        List.copyOf(Objects.requireNonNull(additionsSample, "additions are required"));
    removalsSample = List.copyOf(Objects.requireNonNull(removalsSample, "removals are required"));
    eligibilityChangesSample =
        List.copyOf(
            Objects.requireNonNull(eligibilityChangesSample, "eligibility changes are required"));
    routeChangesSample =
        List.copyOf(Objects.requireNonNull(routeChangesSample, "route changes are required"));
    partitionLoads =
        Map.copyOf(Objects.requireNonNull(partitionLoads, "partition loads are required"));
    if (artifactSizeBytes <= 0 || contentSha256 == null || contentSha256.isBlank()) {
      throw new MarketReferenceBuildException("artifact review identity is required");
    }
    if (deliveryType == null || deliveryType.isBlank()) {
      throw new MarketReferenceBuildException("artifact review delivery type is required");
    }
    validationResults =
        List.copyOf(Objects.requireNonNull(validationResults, "validation results are required"));
  }
}

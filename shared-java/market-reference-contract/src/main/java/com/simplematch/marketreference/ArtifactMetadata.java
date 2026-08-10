package com.simplematch.marketreference;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Version, trading-day, routing, and official-source metadata for one artifact. */
public record ArtifactMetadata(
    int schemaVersion,
    ArtifactReleaseState releaseState,
    LocalDate tradingDay,
    String routingAlgorithmVersion,
    List<SourceProvenance> sourceProvenance) {
  /** Validates metadata and preserves deterministic source ordering. */
  public ArtifactMetadata {
    if (schemaVersion != 1) {
      throw new MarketReferenceValidationException(
          "unsupported artifact schema version: " + schemaVersion);
    }
    Objects.requireNonNull(releaseState, "artifact release state is required");
    Objects.requireNonNull(tradingDay, "trading day is required");
    if (routingAlgorithmVersion == null || routingAlgorithmVersion.isBlank()) {
      throw new MarketReferenceValidationException("routing algorithm version is required");
    }
    sourceProvenance =
        List.copyOf(
            Objects.requireNonNull(sourceProvenance, "source provenance is required").stream()
                .sorted(Comparator.comparing(SourceProvenance::sourceId))
                .toList());
    if (sourceProvenance.isEmpty()) {
      throw new MarketReferenceValidationException(
          "artifact must identify at least one official source");
    }
  }
}

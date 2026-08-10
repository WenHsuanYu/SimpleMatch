package com.simplematch.marketreference;

import java.time.LocalDate;
import java.util.Objects;

/** Immutable retrieval evidence for one official source document. */
public record SourceProvenance(
    String sourceId,
    String sourceUrl,
    LocalDate sourceDate,
    long retrievedAtUnixMs,
    String contentSha256) {
  /** Validates source identity, dates, and byte-level provenance. */
  public SourceProvenance {
    sourceId = requireText(sourceId, "source id");
    sourceUrl = requireText(sourceUrl, "source URL");
    Objects.requireNonNull(sourceDate, "source date is required");
    if (retrievedAtUnixMs <= 0) {
      throw new MarketReferenceValidationException("source retrieval time must be positive");
    }
    contentSha256 = ArtifactChecksum.requireCanonical(contentSha256);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new MarketReferenceValidationException(field + " is required");
    }
    return value.trim();
  }
}

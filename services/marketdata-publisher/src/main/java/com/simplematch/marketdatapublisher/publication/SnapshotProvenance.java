package com.simplematch.marketdatapublisher.publication;

/** Immutable source metadata used to identify and audit published snapshot content. */
public record SnapshotProvenance(
    String sourceIdentity, long sourceTimestampUnixMs, String checksum) {
  /** Validates the durable source metadata. */
  public SnapshotProvenance {
    if (sourceIdentity == null || sourceIdentity.isBlank()) {
      throw new IllegalArgumentException("source identity is required");
    }
    if (sourceTimestampUnixMs <= 0) {
      throw new IllegalArgumentException("source timestamp must be positive");
    }
    if (checksum == null || checksum.isBlank()) {
      throw new IllegalArgumentException("checksum is required");
    }
  }
}

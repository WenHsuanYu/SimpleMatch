package com.simplematch.marketdatapublisher.publication;

import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Durable snapshot metadata and its immutable normalized content. */
public record PublishedMarketSnapshot(
    SnapshotIdentity identity,
    SnapshotProvenance provenance,
    String canonicalContent,
    SnapshotPublicationState publication) {
  /** Validates the database-facing snapshot state. */
  public PublishedMarketSnapshot {
    Objects.requireNonNull(identity, "snapshot identity is required");
    Objects.requireNonNull(provenance, "snapshot provenance is required");
    canonicalContent = requireText(canonicalContent, "canonical content");
    Objects.requireNonNull(publication, "publication state is required");
  }

  /** Creates active persisted state from prepared content and transaction-owned values. */
  public static PublishedMarketSnapshot active(
      UUID snapshotId, long version, PreparedMarketSnapshot prepared, Instant publishedAt) {
    return new PublishedMarketSnapshot(
        new SnapshotIdentity(snapshotId, prepared.tradingDay(), version),
        new SnapshotProvenance(
            prepared.sourceIdentity(), prepared.sourceTimestampUnixMs(), prepared.checksum()),
        prepared.canonicalContent(),
        new SnapshotPublicationState(true, publishedAt));
  }

  /** Returns whether this snapshot is the active row for its trading day. */
  public boolean active() {
    return publication.active();
  }

  /** Returns the snapshot identifier used by publication results and health details. */
  public UUID snapshotId() {
    return identity.snapshotId();
  }

  /** Returns the trading day represented by this snapshot. */
  public LocalDate tradingDay() {
    return identity.tradingDay();
  }

  /** Returns the immutable version allocated for the trading day. */
  public long version() {
    return identity.version();
  }

  /** Returns the source identity used for duplicate detection. */
  public String sourceIdentity() {
    return provenance.sourceIdentity();
  }

  /** Returns the source timestamp carried by the imported snapshot. */
  public long sourceTimestampUnixMs() {
    return provenance.sourceTimestampUnixMs();
  }

  /** Returns the checksum of the canonical snapshot content. */
  public String checksum() {
    return provenance.checksum();
  }

  /** Returns the timestamp at which this snapshot was published. */
  public Instant publishedAt() {
    return publication.publishedAt();
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }
}

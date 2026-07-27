package com.simplematch.marketdatapublisher.publication;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable result of one market-snapshot publication request. */
public record SnapshotPublicationResult(UUID snapshotId, LocalDate tradingDay, long version, boolean duplicate) {
  /** Validates the public publication result. */
  public SnapshotPublicationResult {
    Objects.requireNonNull(snapshotId, "snapshot id is required");
    Objects.requireNonNull(tradingDay, "trading day is required");
    if (version <= 0) {
      throw new IllegalArgumentException("snapshot version must be positive");
    }
  }
}

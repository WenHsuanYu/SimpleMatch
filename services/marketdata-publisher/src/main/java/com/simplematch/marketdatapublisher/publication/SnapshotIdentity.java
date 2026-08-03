package com.simplematch.marketdatapublisher.publication;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable identity of one published market snapshot version. */
public record SnapshotIdentity(UUID snapshotId, LocalDate tradingDay, long version) {
  /** Validates the durable snapshot identity. */
  public SnapshotIdentity {
    Objects.requireNonNull(snapshotId, "snapshot id is required");
    Objects.requireNonNull(tradingDay, "trading day is required");
    if (version <= 0) {
      throw new IllegalArgumentException("snapshot version must be positive");
    }
  }
}

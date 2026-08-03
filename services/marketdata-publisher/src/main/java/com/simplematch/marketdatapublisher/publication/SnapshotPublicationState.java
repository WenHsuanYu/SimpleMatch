package com.simplematch.marketdatapublisher.publication;

import java.time.Instant;
import java.util.Objects;

/** Immutable activation state and publication timestamp for one snapshot row. */
public record SnapshotPublicationState(boolean active, Instant publishedAt) {
  /** Validates the durable publication state. */
  public SnapshotPublicationState {
    Objects.requireNonNull(publishedAt, "published at is required");
  }
}

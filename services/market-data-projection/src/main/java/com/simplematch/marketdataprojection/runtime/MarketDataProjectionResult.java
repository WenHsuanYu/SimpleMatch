package com.simplematch.marketdataprojection.runtime;

import java.util.Objects;
import java.util.Optional;

/** Outcome of applying one final Matching Event to the rebuildable projection. */
public record MarketDataProjectionResult(
    boolean applied, Optional<MarketDataSnapshotView> snapshot) {
  /** Defensively requires explicit duplicate handling at the projection seam. */
  public MarketDataProjectionResult {
    Objects.requireNonNull(snapshot, "snapshot");
    if (applied != snapshot.isPresent()) {
      throw new IllegalArgumentException("only an applied projection may expose a new snapshot");
    }
  }

  /** Returns an applied outcome with the complete new public snapshot. */
  public static MarketDataProjectionResult applied(MarketDataSnapshotView snapshot) {
    return new MarketDataProjectionResult(true, Optional.of(snapshot));
  }

  /** Returns an exact raw-byte replay outcome without publishing another snapshot. */
  public static MarketDataProjectionResult duplicate() {
    return new MarketDataProjectionResult(false, Optional.empty());
  }
}

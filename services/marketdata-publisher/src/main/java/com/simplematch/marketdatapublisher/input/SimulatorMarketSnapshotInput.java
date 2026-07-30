package com.simplematch.marketdatapublisher.input;

import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;
import java.util.Objects;

/** Supplies a caller-selected immutable snapshot for deterministic local simulation. */
public final class SimulatorMarketSnapshotInput implements MarketSnapshotInput {
  private final PreparedMarketSnapshot snapshot;

  /** Creates a simulator input with prevalidated snapshot content. */
  public SimulatorMarketSnapshotInput(PreparedMarketSnapshot snapshot) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  /** Returns the same immutable content on every deterministic simulator step. */
  @Override
  public PreparedMarketSnapshot nextSnapshot() {
    return snapshot;
  }
}

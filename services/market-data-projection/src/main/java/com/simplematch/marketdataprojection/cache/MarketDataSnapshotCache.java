package com.simplematch.marketdataprojection.cache;

/** Materializes rebuildable snapshots in a cache that never decides matching or admission. */
public interface MarketDataSnapshotCache {
  /** Stores one complete snapshot under its deterministic market-data key. */
  void put(MarketDataSnapshotCacheEntry entry);

  /** Clears only this projection's namespaced keys before a controlled replay. */
  void clear();
}

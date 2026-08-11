package com.simplematch.marketdataprojection.cache;

import com.simplematch.marketdataprojection.store.MarketDataProjectionStore;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repairs Redis snapshot materialization from the durable projection without affecting matching.
 */
public final class MarketDataSnapshotCacheRefresher {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(MarketDataSnapshotCacheRefresher.class);
  private final MarketDataProjectionStore store;
  private final MarketDataSnapshotCache cache;
  private final int batchSize;

  /** Creates a bounded repair loop over snapshots committed by the projection transaction. */
  public MarketDataSnapshotCacheRefresher(
      MarketDataProjectionStore store, MarketDataSnapshotCache cache, int batchSize) {
    this.store = Objects.requireNonNull(store, "store");
    this.cache = Objects.requireNonNull(cache, "cache");
    if (batchSize <= 0) {
      throw new IllegalArgumentException("cache refresh batch size must be positive");
    }
    this.batchSize = batchSize;
  }

  /**
   * Refreshes pending cache entries independently; a failure leaves the durable snapshot unchanged.
   */
  public void refreshPending() {
    for (MarketDataSnapshotCacheEntry entry : store.pendingRedisSnapshots(batchSize)) {
      try {
        cache.put(entry);
        store.markRedisSnapshotCached(entry);
      } catch (RuntimeException failure) {
        LOGGER.warn("market-data Redis snapshot remains pending key={}", entry.redisKey(), failure);
      }
    }
  }
}

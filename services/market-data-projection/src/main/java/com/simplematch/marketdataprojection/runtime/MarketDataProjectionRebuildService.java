package com.simplematch.marketdataprojection.runtime;

import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCache;
import com.simplematch.marketdataprojection.store.MarketDataProjectionStore;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/** Provides the explicit local half of a non-critical projection rebuild procedure. */
public final class MarketDataProjectionRebuildService {
  private final MarketDataProjectionStore store;
  private final TransactionTemplate transactionTemplate;
  private final Optional<MarketDataSnapshotCache> cache;

  /** Creates the explicit operator-only projection reset seam. */
  public MarketDataProjectionRebuildService(
      MarketDataProjectionStore store,
      TransactionTemplate transactionTemplate,
      Optional<MarketDataSnapshotCache> cache) {
    this.store = Objects.requireNonNull(store, "store");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  /**
   * Clears reconstructible durable state and its optional cache namespace in one local reset
   * transaction; the operator separately resets this consumer group offsets.
   */
  public void resetForReplay() {
    transactionTemplate.executeWithoutResult(
        ignored -> {
          store.resetForReplay();
          cache.ifPresent(MarketDataSnapshotCache::clear);
        });
  }
}

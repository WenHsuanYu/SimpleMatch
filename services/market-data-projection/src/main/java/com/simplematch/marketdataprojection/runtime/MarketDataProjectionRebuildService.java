package com.simplematch.marketdataprojection.runtime;

import com.simplematch.marketdataprojection.store.MarketDataProjectionStore;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

/** Provides the explicit local half of a non-critical projection rebuild procedure. */
public final class MarketDataProjectionRebuildService {
  private final MarketDataProjectionStore store;
  private final TransactionTemplate transactionTemplate;

  /** Creates the explicit operator-only projection reset seam. */
  public MarketDataProjectionRebuildService(
      MarketDataProjectionStore store, TransactionTemplate transactionTemplate) {
    this.store = Objects.requireNonNull(store, "store");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
  }

  /**
   * Clears reconstructible state atomically; the operator separately resets this consumer group
   * offsets.
   */
  public void resetForReplay() {
    transactionTemplate.executeWithoutResult(ignored -> store.resetForReplay());
  }
}

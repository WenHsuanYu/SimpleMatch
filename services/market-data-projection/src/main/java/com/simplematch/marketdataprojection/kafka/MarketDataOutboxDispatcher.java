package com.simplematch.marketdataprojection.kafka;

import com.simplematch.marketdataprojection.store.MarketDataProjectionStore;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers committed snapshot intents at least once without entering the Matching consumer hot
 * path.
 */
public final class MarketDataOutboxDispatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(MarketDataOutboxDispatcher.class);
  private final MarketDataProjectionStore store;
  private final MarketDataEventPublisher publisher;
  private final Clock clock;
  private final int batchSize;

  /** Creates the serial output dispatcher for one projection database. */
  public MarketDataOutboxDispatcher(
      MarketDataProjectionStore store,
      MarketDataEventPublisher publisher,
      Clock clock,
      int batchSize) {
    this.store = Objects.requireNonNull(store, "store");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (batchSize <= 0) {
      throw new IllegalArgumentException("outbox batch size must be positive");
    }
    this.batchSize = batchSize;
  }

  /** Publishes pending snapshots in order and leaves the first failed record intact for replay. */
  public void dispatchPending() {
    for (MarketDataOutboxRecord record : store.pendingOutbox(batchSize)) {
      try {
        publisher.publish(record);
        store.markOutboxPublished(record, clock.millis());
      } catch (RuntimeException failure) {
        LOGGER.warn(
            "market-data snapshot publication remains pending topic={} key={}",
            record.topic(),
            record.key(),
            failure);
        return;
      }
    }
  }
}

package com.simplematch.marketdataprojection.runtime;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.marketdataprojection.store.MarketDataProjectionStore;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Owns the local transaction that persists one rebuildable market-data view and its outbox event.
 */
public class MarketDataProjectionApplicationService implements MarketDataProjectionHandler {
  private final MarketDataProjectionStore store;
  private final TransactionOperations transactionOperations;
  private final Clock clock;

  /** Creates the public application seam for one ordered final Matching Event. */
  public MarketDataProjectionApplicationService(
      MarketDataProjectionStore store, TransactionOperations transactionOperations, Clock clock) {
    this.store = store;
    this.transactionOperations = transactionOperations;
    this.clock = clock;
  }

  /**
   * Commits one snapshot and its durable publication intent before Kafka may acknowledge the input.
   */
  @Override
  public MarketDataProjectionResult project(
      FinalMatchingEventEnvelope envelope, int kafkaPartition, long kafkaOffset) {
    final long observedAtUnixMs = clock.millis();
    final MarketDataProjectionResult result;
    try {
      result =
          transactionOperations.execute(
              ignored ->
                  store.project(
                      Objects.requireNonNull(envelope, "envelope"),
                      kafkaPartition,
                      kafkaOffset,
                      observedAtUnixMs));
    } catch (MarketDataProjectionGapException gap) {
      transactionOperations.executeWithoutResult(
          ignored -> store.markResyncRequired(kafkaPartition, kafkaOffset, observedAtUnixMs));
      throw gap;
    }
    if (result == null) {
      throw new IllegalStateException("market-data projection transaction returned no result");
    }
    return result;
  }
}

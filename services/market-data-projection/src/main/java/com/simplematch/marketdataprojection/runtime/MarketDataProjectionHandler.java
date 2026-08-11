package com.simplematch.marketdataprojection.runtime;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;

/**
 * Applies one validated final Matching Event before the non-critical consumer commits its offset.
 */
@FunctionalInterface
public interface MarketDataProjectionHandler {
  /** Persists the complete rebuildable snapshot for the supplied ordered Kafka position. */
  MarketDataProjectionResult project(
      FinalMatchingEventEnvelope envelope, int kafkaPartition, long kafkaOffset);
}

package com.simplematch.marketdataprojection.kafka;

import com.simplematch.config.delivery.DeliveryRecord;
import java.time.Instant;

/**
 * Schedules a delayed retry for one rebuildable projection record after its source offset commits.
 */
@FunctionalInterface
public interface MarketDataProjectionRetryScheduler {
  /** Schedules the exact received bytes for later retry at the requested instant. */
  void schedule(DeliveryRecord record, Instant retryAt, Runnable retry);
}

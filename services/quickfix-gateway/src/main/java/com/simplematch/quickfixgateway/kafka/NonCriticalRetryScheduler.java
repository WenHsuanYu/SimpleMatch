package com.simplematch.quickfixgateway.kafka;

import com.simplematch.config.delivery.DeliveryRecord;
import java.time.Instant;

/** Schedules a rebuildable projection retry without blocking its source partition. */
@FunctionalInterface
public interface NonCriticalRetryScheduler {
  /** Schedules the supplied delivery attempt for the requested instant. */
  void schedule(DeliveryRecord record, Instant retryAt, Runnable retry);
}

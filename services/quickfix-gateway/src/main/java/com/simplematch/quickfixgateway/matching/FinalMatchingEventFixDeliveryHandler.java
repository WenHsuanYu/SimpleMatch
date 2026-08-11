package com.simplematch.quickfixgateway.matching;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;

/** Application boundary that durably accepts one final Matching Event for QuickFIX delivery. */
@FunctionalInterface
public interface FinalMatchingEventFixDeliveryHandler {
  /**
   * Persists the raw event and every required client-report intent before Kafka acknowledgement.
   */
  FinalMatchingEventFixDeliveryOutcome persist(
      FinalMatchingEventEnvelope envelope, int kafkaPartition, long kafkaOffset);
}

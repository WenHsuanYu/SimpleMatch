package com.simplematch.persistence.matching;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;

/** Applies one validated final Matching Event before its Kafka offset may be acknowledged. */
@FunctionalInterface
public interface MatchingEventPersistenceHandler {
  /** Persists all local final-event effects for the received Kafka position. */
  MatchingEventPersistenceOutcome persist(
      FinalMatchingEventEnvelope envelope, int kafkaPartition, long kafkaOffset);
}

package com.simplematch.accountservice.matching;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;

/** Applies one validated final Matching Event before the input Kafka offset is acknowledged. */
@FunctionalInterface
public interface FinalMatchingEventAccountHandler {
  /** Applies all Account Authority effects for one Matching partition position. */
  FinalMatchingEventAccountOutcome apply(
      FinalMatchingEventEnvelope envelope, int kafkaPartition, long kafkaOffset);
}

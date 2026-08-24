package com.simplematch.accountservice.matching;

/** Applies one Account-owned final Matching Event command before Kafka acknowledgment. */
@FunctionalInterface
public interface FinalMatchingEventAccountHandler {
  /** Applies all Account Authority effects for one Matching partition position. */
  FinalMatchingEventAccountOutcome apply(
      FinalMatchingEventAccountCommand command, int kafkaPartition, long kafkaOffset);
}

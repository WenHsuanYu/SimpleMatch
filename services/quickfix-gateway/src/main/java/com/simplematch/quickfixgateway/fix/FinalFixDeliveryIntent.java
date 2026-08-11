package com.simplematch.quickfixgateway.fix;

import java.util.Objects;

/**
 * One durable, replayable client report intent derived from a final Matching Event.
 *
 * @param identity deterministic delivery and source-event identities
 * @param recipient FIX session and recipient order facts
 * @param report stable report facts, including its ExecID
 * @param sourcePartition final Matching Event Kafka partition
 * @param sourceOffset final Matching Event Kafka offset
 * @param createdAtUnixMs durable-intent creation time
 */
public record FinalFixDeliveryIntent(
    FinalFixDeliveryIdentity identity,
    FinalFixDeliveryRecipient recipient,
    FinalFixDeliveryReport report,
    int sourcePartition,
    long sourceOffset,
    long createdAtUnixMs) {
  /** Requires a valid direct-partition final-event source position. */
  public FinalFixDeliveryIntent {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(recipient, "recipient");
    Objects.requireNonNull(report, "report");
    if (sourcePartition < 0 || sourcePartition > 14 || sourceOffset < 0 || createdAtUnixMs < 0) {
      throw new IllegalArgumentException("final FIX delivery source position is invalid");
    }
  }
}

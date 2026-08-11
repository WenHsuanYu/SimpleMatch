package com.simplematch.quickfixgateway.operations;

import java.time.Instant;

/** Gateway-domain observation of one fixed Matching partition owner. */
public record MatchingPartitionStatus(
    int partitionId,
    String ownerId,
    OperationalComponentState state,
    TradingIdentity identity,
    boolean ownershipPermit,
    boolean recoveryComplete,
    long committedOffset,
    long endOffset,
    Instant observedAt,
    String reason) {
  /** Validates the normalized facts reported for one Matching partition. */
  public MatchingPartitionStatus {
    if (partitionId < 0) {
      throw new IllegalArgumentException("partitionId must not be negative");
    }
    ownerId = OperationalStatusValidation.requiredText(ownerId, "ownerId");
    state = OperationalStatusValidation.required(state, "state");
    identity = OperationalStatusValidation.required(identity, "identity");
    committedOffset = OperationalStatusValidation.nonNegative(committedOffset, "committedOffset");
    endOffset = OperationalStatusValidation.nonNegative(endOffset, "endOffset");
    observedAt = OperationalStatusValidation.required(observedAt, "observedAt");
    reason = OperationalStatusValidation.requiredText(reason, "reason");
  }
}

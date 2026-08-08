package com.simplematch.config.delivery;

/** Outcome a consumer adapter must apply to one delivery attempt. */
public enum DeliveryDecision {
  /** The handler completed and the consumer may commit this offset. */
  COMMIT,
  /** The handler failed and the consumer must seek and retry this same offset. */
  RETRY_IN_PLACE,
  /** Retry budget is exhausted and the affected partition must be paused. */
  QUARANTINED,
  /** A prior failed offset blocks this partition; no later offset may commit. */
  BLOCKED
}

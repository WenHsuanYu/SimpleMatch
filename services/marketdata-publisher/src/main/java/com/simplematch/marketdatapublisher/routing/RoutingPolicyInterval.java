package com.simplematch.marketdatapublisher.routing;

import java.time.Instant;
import java.util.Objects;

/** Half-open UTC interval during which one routing policy is authoritative. */
public record RoutingPolicyInterval(Instant effectiveFrom, Instant effectiveUntil) {
  /** Requires a non-empty interval so adjacent policies can be composed without ambiguity. */
  public RoutingPolicyInterval {
    Objects.requireNonNull(effectiveFrom, "effective from");
    Objects.requireNonNull(effectiveUntil, "effective until");
    if (!effectiveFrom.isBefore(effectiveUntil)) {
      throw new RoutingPolicyValidationException(
          "routing policy effective interval must have a positive duration");
    }
  }

  /** Returns whether the instant belongs to this inclusive-start, exclusive-end interval. */
  public boolean contains(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return !instant.isBefore(effectiveFrom) && instant.isBefore(effectiveUntil);
  }

  /** Returns whether this interval overlaps another half-open interval. */
  public boolean overlaps(RoutingPolicyInterval other) {
    Objects.requireNonNull(other, "other");
    return effectiveFrom.isBefore(other.effectiveUntil)
        && other.effectiveFrom.isBefore(effectiveUntil);
  }
}

package com.simplematch.riskservice.routing;

import java.time.Instant;
import java.util.Objects;

/** Half-open effective interval copied from the published policy. */
public record RoutingPolicyProjectionInterval(Instant effectiveFrom, Instant effectiveUntil) {
  /** Rejects an empty or reversed effective interval. */
  public RoutingPolicyProjectionInterval {
    Objects.requireNonNull(effectiveFrom, "effective from");
    Objects.requireNonNull(effectiveUntil, "effective until");
    if (!effectiveFrom.isBefore(effectiveUntil)) {
      throw new RoutingPolicyProjectionValidationException(
          "routing policy effective interval must be positive");
    }
  }

  /** Returns whether the instant is within the inclusive-start, exclusive-end interval. */
  public boolean contains(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return !instant.isBefore(effectiveFrom) && instant.isBefore(effectiveUntil);
  }
}

package com.simplematch.marketdatapublisher.routing;

/** Signals that a routing policy is incomplete or violates its domain invariants. */
public final class RoutingPolicyValidationException extends IllegalArgumentException {
  /** Creates a validation failure with an actionable domain message. */
  public RoutingPolicyValidationException(String message) {
    super(message);
  }
}

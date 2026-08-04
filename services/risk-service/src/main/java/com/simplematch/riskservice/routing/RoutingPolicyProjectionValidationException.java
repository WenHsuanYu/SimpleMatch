package com.simplematch.riskservice.routing;

/** Signals that a serialized routing-policy publication cannot become Risk local state. */
public final class RoutingPolicyProjectionValidationException extends IllegalArgumentException {
  /** Creates a validation failure with a stable diagnostic message. */
  public RoutingPolicyProjectionValidationException(String message) {
    super(message);
  }

  /** Creates a validation failure while retaining the wire-decoding cause. */
  public RoutingPolicyProjectionValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}

package com.simplematch.marketdatapublisher.routing;

/** Checked failure from a routing-policy publication dependency that must roll back the outcome. */
public final class RoutingPolicyPublicationFailure extends Exception {
  /** Creates a failure with the local publication detail. */
  public RoutingPolicyPublicationFailure(String message) {
    super(message);
  }

  /** Creates a failure while retaining the dependency cause. */
  public RoutingPolicyPublicationFailure(String message, Throwable cause) {
    super(message, cause);
  }
}

package com.simplematch.marketdatapublisher.routing;

/** Signals a conflicting identity, source, or effective interval during policy publication. */
public final class RoutingPolicyPublicationConflictException extends RuntimeException {
  /** Creates a deterministic conflict failure for operator and caller handling. */
  public RoutingPolicyPublicationConflictException(String message) {
    super(message);
  }
}

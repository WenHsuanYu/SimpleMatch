package com.simplematch.marketdatapublisher.routing;

/** Durable outbox operations used inside the routing-policy publication transaction. */
public interface RoutingPolicyOutbox {
  /** Inserts one serialized policy event into the service-local outbox. */
  void insert(RoutingPolicyOutboxRecord record) throws RoutingPolicyPublicationFailure;
}

package com.simplematch.riskservice.routing;

/** Public projection seam used by the ordered Routing Policy consumer. */
@FunctionalInterface
public interface RoutingPolicyProjector {
  /**
   * Projects one serialized Routing Policy event atomically.
   *
   * @param payload serialized Routing Policy protobuf bytes
   * @return the durable projection result
   */
  RoutingPolicyProjectionResult project(byte[] payload);
}

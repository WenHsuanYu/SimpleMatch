package com.simplematch.riskservice.routing;

import java.util.Objects;
import java.util.UUID;

/** Result of an idempotent local routing-policy projection command. */
public record RoutingPolicyProjectionResult(UUID routingPolicyId, boolean duplicate) {
  /** Requires the identity of the projected policy. */
  public RoutingPolicyProjectionResult {
    Objects.requireNonNull(routingPolicyId, "routing policy id");
  }
}

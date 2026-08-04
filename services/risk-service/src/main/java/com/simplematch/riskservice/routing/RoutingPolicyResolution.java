package com.simplematch.riskservice.routing;

import java.util.Objects;
import java.util.UUID;

/** Authoritative policy identity and explicit partition returned by one local lookup. */
public record RoutingPolicyResolution(UUID routingPolicyId, int routingPartition) {
  /** Requires a policy identity and non-negative partition. */
  public RoutingPolicyResolution {
    Objects.requireNonNull(routingPolicyId, "routing policy id");
    if (routingPartition < 0) {
      throw new RoutingPolicyProjectionValidationException(
          "resolved routing partition must be non-negative");
    }
  }
}

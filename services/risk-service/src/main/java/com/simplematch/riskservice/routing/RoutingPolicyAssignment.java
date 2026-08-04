package com.simplematch.riskservice.routing;

import java.util.Objects;

/** One normalized instrument assignment in the Risk-local policy projection. */
public record RoutingPolicyAssignment(RoutingInstrument instrument, int routingPartition) {
  /** Requires an instrument and a non-negative explicit route. */
  public RoutingPolicyAssignment {
    Objects.requireNonNull(instrument, "instrument");
    if (routingPartition < 0) {
      throw new RoutingPolicyProjectionValidationException(
          "routing partition must be non-negative");
    }
  }
}

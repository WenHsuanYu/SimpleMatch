package com.simplematch.marketdatapublisher.routing;

import com.simplematch.marketdatapublisher.snapshot.InstrumentIdentity;
import java.util.Objects;

/** Binds one normalized market instrument to its deterministic Kafka partition. */
public record RoutingAssignment(InstrumentIdentity instrument, int routingPartition) {
  /** Requires an instrument and a non-negative partition; the policy validates the upper bound. */
  public RoutingAssignment {
    Objects.requireNonNull(instrument, "instrument");
    if (routingPartition < 0) {
      throw new RoutingPolicyValidationException("routing partition must be non-negative");
    }
  }
}

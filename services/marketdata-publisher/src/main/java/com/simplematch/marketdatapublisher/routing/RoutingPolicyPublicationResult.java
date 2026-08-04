package com.simplematch.marketdatapublisher.routing;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Stable result returned by an idempotent routing-policy publication command. */
public record RoutingPolicyPublicationResult(
    UUID routingPolicyId, LocalDate tradingDay, boolean duplicate) {
  /** Requires the identity of the durable policy result. */
  public RoutingPolicyPublicationResult {
    Objects.requireNonNull(routingPolicyId, "routing policy id");
    Objects.requireNonNull(tradingDay, "trading day");
  }
}

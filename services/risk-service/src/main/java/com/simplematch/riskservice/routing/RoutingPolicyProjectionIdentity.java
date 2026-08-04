package com.simplematch.riskservice.routing;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Durable identity and Market Reference provenance retained by Risk. */
public record RoutingPolicyProjectionIdentity(
    UUID routingPolicyId, UUID sourceMarketSnapshotId, LocalDate tradingDay) {
  /** Requires both immutable identities and the policy trading day. */
  public RoutingPolicyProjectionIdentity {
    Objects.requireNonNull(routingPolicyId, "routing policy id");
    Objects.requireNonNull(sourceMarketSnapshotId, "source market snapshot id");
    Objects.requireNonNull(tradingDay, "trading day");
  }
}

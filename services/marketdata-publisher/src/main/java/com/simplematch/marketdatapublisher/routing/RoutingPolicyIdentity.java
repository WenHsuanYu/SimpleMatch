package com.simplematch.marketdatapublisher.routing;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Stable identity and source provenance for one immutable routing policy. */
public record RoutingPolicyIdentity(
    UUID routingPolicyId, UUID sourceMarketSnapshotId, LocalDate tradingDay) {
  /** Requires an opaque policy id, its source snapshot, and the applicable trading day. */
  public RoutingPolicyIdentity {
    Objects.requireNonNull(routingPolicyId, "routing policy id");
    Objects.requireNonNull(sourceMarketSnapshotId, "source market snapshot id");
    Objects.requireNonNull(tradingDay, "trading day");
  }
}

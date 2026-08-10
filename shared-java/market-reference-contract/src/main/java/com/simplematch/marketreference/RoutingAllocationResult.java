package com.simplematch.marketreference;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete allocation output plus bounded capacity and route-diff diagnostics. */
public record RoutingAllocationResult(
    RoutingPolicy routingPolicy,
    Map<Integer, Integer> partitionLoads,
    List<RouteChange> routeChanges) {
  /** Preserves immutable diagnostics for approval reporting. */
  public RoutingAllocationResult {
    Objects.requireNonNull(routingPolicy, "allocated routing policy is required");
    partitionLoads =
        Map.copyOf(Objects.requireNonNull(partitionLoads, "partition loads are required"));
    routeChanges = List.copyOf(Objects.requireNonNull(routeChanges, "route changes are required"));
  }
}

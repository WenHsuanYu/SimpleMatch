package com.simplematch.marketreference;

import java.util.Objects;

/** One operator-visible addition, removal, or retained route comparison. */
public record RouteChange(
    InstrumentRef instrument, Integer previousPartitionId, Integer partitionId) {
  /** Requires an instrument and at least one side of the route comparison. */
  public RouteChange {
    Objects.requireNonNull(instrument, "route-change instrument is required");
    if (previousPartitionId == null && partitionId == null) {
      throw new MarketReferenceValidationException(
          "route change must contain a previous or current route");
    }
  }
}

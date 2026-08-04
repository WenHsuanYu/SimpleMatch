package com.simplematch.marketdatapublisher.routing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations directed by the routing-policy publication transaction owner. */
public interface RoutingPolicyRepository {
  /** Rehydrates a complete policy by its immutable identity. */
  Optional<RoutingPolicy> findById(UUID routingPolicyId);

  /** Finds the complete active policy that applies at one instant for a trading day. */
  Optional<RoutingPolicy> findApplicable(LocalDate tradingDay, Instant at);

  /** Finds the most recently ended or future active policy for one trading day. */
  Optional<RoutingPolicy> findLatestForTradingDay(LocalDate tradingDay);

  /** Finds the newest active policy across trading days for stale-state diagnostics. */
  Optional<RoutingPolicy> findLatestActive();

  /** Locks the source market snapshot and verifies that it belongs to the policy trading day. */
  void lockSourceSnapshot(UUID sourceMarketSnapshotId, LocalDate tradingDay);

  /** Locks existing policies for the day and reports whether the interval overlaps one. */
  boolean existsOverlappingForUpdate(LocalDate tradingDay, RoutingPolicyInterval interval);

  /** Inserts the policy and its complete instrument assignment set. */
  void insert(RoutingPolicy policy, Instant publishedAt);
}

package com.simplematch.marketdatapublisher.routing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

  /** Locks and rehydrates every active policy for the day for continuity validation. */
  List<RoutingPolicy> findAllForTradingDayForUpdate(LocalDate tradingDay);

  /** Inserts the policy and its complete instrument assignment set. */
  void insert(RoutingPolicy policy, Instant publishedAt);
}

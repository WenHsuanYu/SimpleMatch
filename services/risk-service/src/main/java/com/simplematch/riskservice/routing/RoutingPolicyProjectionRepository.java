package com.simplematch.riskservice.routing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Risk-local persistence seam for complete staged and active policy projections. */
public interface RoutingPolicyProjectionRepository {
  /** Rehydrates one complete policy projection by immutable identity. */
  Optional<RoutingPolicyProjection> findById(UUID routingPolicyId);

  /** Finds the active projection applicable at one instant for a trading day. */
  Optional<RoutingPolicyProjection> findApplicable(LocalDate tradingDay, Instant at);

  /** Finds the newest active projection for stale-state diagnostics. */
  Optional<RoutingPolicyProjection> findLatestActive();

  /** Persists parent and assignments as inactive staging state. */
  void insertStaged(RoutingPolicyProjection projection, Instant receivedAt);

  /** Makes a fully persisted staged projection visible to local lookups. */
  void activate(UUID routingPolicyId);
}

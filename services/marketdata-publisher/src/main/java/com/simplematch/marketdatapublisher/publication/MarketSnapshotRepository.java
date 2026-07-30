package com.simplematch.marketdatapublisher.publication;

import java.time.LocalDate;
import java.util.Optional;

/** Persistence operations directed by the snapshot publication transaction owner. */
public interface MarketSnapshotRepository {
  /** Finds a previously published source checksum so a duplicate can reproduce its result. */
  Optional<PublishedMarketSnapshot> findBySourceIdentityAndChecksum(
      String sourceIdentity, String checksum);

  /** Finds the active snapshot without acquiring a publication lock. */
  Optional<PublishedMarketSnapshot> findActive(LocalDate tradingDay);

  /** Finds the newest active snapshot so readiness can distinguish stale data from missing data. */
  Optional<PublishedMarketSnapshot> findLatestActive();

  /** Locks the current active snapshot for a trading day when one exists. */
  Optional<PublishedMarketSnapshot> findActiveForUpdate(LocalDate tradingDay);

  /** Allocates the next immutable version after the current-day state is locked. */
  long nextVersion(LocalDate tradingDay);

  /** Clears the active marker for the previously active snapshot. */
  void deactivateActive(LocalDate tradingDay);

  /** Inserts the new active snapshot metadata and complete immutable content. */
  void insert(PublishedMarketSnapshot snapshot) throws SnapshotPublicationFailure;
}

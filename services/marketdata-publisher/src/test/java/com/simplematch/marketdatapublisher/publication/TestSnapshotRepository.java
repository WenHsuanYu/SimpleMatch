package com.simplematch.marketdatapublisher.publication;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DuplicateKeyException;

/** Test-only delegating repository that can inject a checked first-write failure. */
final class TestSnapshotRepository implements MarketSnapshotRepository {
  private final MarketSnapshotRepository delegate;
  private boolean failNextInsert;
  private boolean failNextInsertWithConflict;
  private volatile CyclicBarrier nextVersionBarrier;

  TestSnapshotRepository(MarketSnapshotRepository delegate) {
    this.delegate = delegate;
  }

  @Override
  public Optional<PublishedMarketSnapshot> findBySourceIdentityAndChecksum(
      String sourceIdentity, String checksum) {
    return delegate.findBySourceIdentityAndChecksum(sourceIdentity, checksum);
  }

  @Override
  public Optional<PublishedMarketSnapshot> findActive(LocalDate tradingDay) {
    return delegate.findActive(tradingDay);
  }

  @Override
  public Optional<PublishedMarketSnapshot> findLatestActive() {
    return delegate.findLatestActive();
  }

  @Override
  public Optional<PublishedMarketSnapshot> findActiveForUpdate(LocalDate tradingDay) {
    return delegate.findActiveForUpdate(tradingDay);
  }

  @Override
  public long nextVersion(LocalDate tradingDay) {
    final long version = delegate.nextVersion(tradingDay);
    final CyclicBarrier barrier = nextVersionBarrier;
    if (barrier != null) {
      awaitVersionRace(barrier);
    }
    return version;
  }

  @Override
  public void deactivateActive(LocalDate tradingDay) {
    delegate.deactivateActive(tradingDay);
  }

  @Override
  public void insert(PublishedMarketSnapshot snapshot) throws SnapshotPublicationFailure {
    if (failNextInsert) {
      failNextInsert = false;
      throw new SnapshotPublicationFailure("simulated snapshot failure");
    }
    if (failNextInsertWithConflict) {
      failNextInsertWithConflict = false;
      throw new DuplicateKeyException("simulated concurrent publication");
    }
    delegate.insert(snapshot);
  }

  void failNextInsertWithCheckedFailure() {
    failNextInsert = true;
  }

  void failNextInsertWithConflict() {
    failNextInsertWithConflict = true;
  }

  void synchronizeNextVersionForTwoPublishers() {
    nextVersionBarrier = new CyclicBarrier(2);
  }

  void clearFailures() {
    failNextInsert = false;
    failNextInsertWithConflict = false;
    nextVersionBarrier = null;
  }

  private void awaitVersionRace(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while synchronizing competing snapshot publishers", exception);
    } catch (BrokenBarrierException | TimeoutException exception) {
      throw new IllegalStateException(
          "competing snapshot publisher synchronization failed", exception);
    }
  }
}

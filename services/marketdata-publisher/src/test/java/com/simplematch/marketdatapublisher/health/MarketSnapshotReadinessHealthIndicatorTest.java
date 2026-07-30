package com.simplematch.marketdatapublisher.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.marketdatapublisher.publication.MarketSnapshotRepository;
import com.simplematch.marketdatapublisher.publication.PublishedMarketSnapshot;
import com.simplematch.marketdatapublisher.publication.SnapshotPublicationFailure;
import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class MarketSnapshotReadinessHealthIndicatorTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-27T01:00:00Z"), ZoneOffset.UTC);

  @DisplayName("readiness fails closed when no active snapshot exists for the Taiwan trading day")
  @Test
  void isOutOfServiceWhenSnapshotIsMissing() {
    final MarketSnapshotReadinessHealthIndicator indicator =
        new MarketSnapshotReadinessHealthIndicator(new StubRepository(Optional.empty()), CLOCK);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(indicator.health().getDetails()).containsEntry("reason", "MISSING_ACTIVE_SNAPSHOT");
  }

  @DisplayName(
      "readiness is up only for the active snapshot matching the current Taiwan trading day")
  @Test
  void isUpForCurrentTaiwanTradingDay() {
    final PublishedMarketSnapshot snapshot = snapshot(LocalDate.of(2026, 7, 27));
    final MarketSnapshotReadinessHealthIndicator indicator =
        new MarketSnapshotReadinessHealthIndicator(
            new StubRepository(Optional.of(snapshot)), CLOCK);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    assertThat(indicator.health().getDetails()).containsEntry("snapshotVersion", 1L);
  }

  @DisplayName("readiness fails closed when the active snapshot belongs to an earlier trading day")
  @Test
  void isOutOfServiceForStaleSnapshot() {
    final PublishedMarketSnapshot snapshot = snapshot(LocalDate.of(2026, 7, 24));
    final MarketSnapshotReadinessHealthIndicator indicator =
        new MarketSnapshotReadinessHealthIndicator(
            new StubRepository(Optional.of(snapshot)), CLOCK);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(indicator.health().getDetails()).containsEntry("reason", "STALE_ACTIVE_SNAPSHOT");
  }

  @DisplayName("readiness fails closed when the stored content does not match its durable checksum")
  @Test
  void isOutOfServiceForInvalidCurrentSnapshot() {
    final PublishedMarketSnapshot snapshot =
        new PublishedMarketSnapshot(
            UUID.randomUUID(),
            LocalDate.of(2026, 7, 27),
            1,
            "source",
            1,
            "a".repeat(64),
            "{}",
            true,
            CLOCK.instant());
    final MarketSnapshotReadinessHealthIndicator indicator =
        new MarketSnapshotReadinessHealthIndicator(
            new StubRepository(Optional.of(snapshot)), CLOCK);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(indicator.health().getDetails()).containsEntry("reason", "INVALID_ACTIVE_SNAPSHOT");
  }

  private PublishedMarketSnapshot snapshot(LocalDate tradingDay) {
    return new PublishedMarketSnapshot(
        UUID.randomUUID(),
        tradingDay,
        1,
        "source",
        1,
        PreparedMarketSnapshot.checksumFor("{}"),
        "{}",
        true,
        CLOCK.instant());
  }

  private static final class StubRepository implements MarketSnapshotRepository {
    private final Optional<PublishedMarketSnapshot> active;

    private StubRepository(Optional<PublishedMarketSnapshot> active) {
      this.active = active;
    }

    @Override
    public Optional<PublishedMarketSnapshot> findBySourceIdentityAndChecksum(
        String sourceIdentity, String checksum) {
      return Optional.empty();
    }

    @Override
    public Optional<PublishedMarketSnapshot> findActive(LocalDate tradingDay) {
      return active.filter(snapshot -> snapshot.tradingDay().equals(tradingDay));
    }

    @Override
    public Optional<PublishedMarketSnapshot> findLatestActive() {
      return active;
    }

    @Override
    public Optional<PublishedMarketSnapshot> findActiveForUpdate(LocalDate tradingDay) {
      return active;
    }

    @Override
    public long nextVersion(LocalDate tradingDay) {
      return 1;
    }

    @Override
    public void deactivateActive(LocalDate tradingDay) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insert(PublishedMarketSnapshot snapshot) throws SnapshotPublicationFailure {
      throw new UnsupportedOperationException();
    }
  }
}

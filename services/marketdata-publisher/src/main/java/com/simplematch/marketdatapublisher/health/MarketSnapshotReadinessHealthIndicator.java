package com.simplematch.marketdatapublisher.health;

import com.simplematch.marketdatapublisher.publication.MarketSnapshotRepository;
import com.simplematch.marketdatapublisher.publication.PublishedMarketSnapshot;
import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * Fails closed until a current Taiwan trading-day snapshot is durably active.
 */
public final class MarketSnapshotReadinessHealthIndicator implements HealthIndicator {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final MarketSnapshotRepository snapshots;
    private final Clock clock;

    /**
     * Creates a readiness indicator whose clock defines the current Taiwan market date.
     */
    public MarketSnapshotReadinessHealthIndicator(MarketSnapshotRepository snapshots, Clock clock) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns an out-of-service health result for missing, stale, or invalid authoritative reference data.
     */
    @Override
    public Health health() {
        final LocalDate expectedTradingDay = clock.instant().atZone(TAIPEI).toLocalDate();
        final Optional<PublishedMarketSnapshot> current = snapshots.findActive(expectedTradingDay);
        if (current.isPresent()) {
            final PublishedMarketSnapshot snapshot = current.orElseThrow();
            if (!PreparedMarketSnapshot.checksumFor(snapshot.canonicalContent()).equals(snapshot.checksum())) {
                return Health.outOfService()
                        .withDetail("reason", "INVALID_ACTIVE_SNAPSHOT")
                        .withDetail("snapshotId", snapshot.snapshotId().toString())
                        .withDetail("tradingDay", snapshot.tradingDay().toString())
                        .build();
            }
            return Health.up()
                    .withDetail("snapshotId", snapshot.snapshotId().toString())
                    .withDetail("snapshotVersion", snapshot.version())
                    .withDetail("tradingDay", snapshot.tradingDay().toString())
                    .build();
        }
        final Optional<PublishedMarketSnapshot> latest = snapshots.findLatestActive();
        if (latest.isEmpty()) {
            return Health.outOfService()
                    .withDetail("reason", "MISSING_ACTIVE_SNAPSHOT")
                    .withDetail("expectedTradingDay", expectedTradingDay.toString())
                    .build();
        }
        final PublishedMarketSnapshot snapshot = latest.orElseThrow();
        return Health.outOfService()
                .withDetail("reason", "STALE_ACTIVE_SNAPSHOT")
                .withDetail("expectedTradingDay", expectedTradingDay.toString())
                .withDetail("snapshotTradingDay", snapshot.tradingDay().toString())
                .build();
    }
}

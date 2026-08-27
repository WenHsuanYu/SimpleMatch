package com.simplematch.persistence.kafka;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Exposes quarantine-aware readiness without treating an idle market as degraded. */
public final class PersistenceMatchingEventsHealthIndicator implements HealthIndicator {
  private final PersistenceMatchingEventStatus status;

  /** Creates the final-event critical-consumer readiness indicator. */
  public PersistenceMatchingEventsHealthIndicator(PersistenceMatchingEventStatus status) {
    this.status = status;
  }

  @Override
  public Health health() {
    final Health.Builder builder =
        status.state() == PersistenceMatchingEventStatus.State.READY
            ? Health.up()
            : Health.outOfService();
    builder
        .withDetail("state", status.state().name())
        .withDetail("committedOffsets", status.committedOffsets())
        .withDetail("oldestUnprocessedAgeMillis", status.oldestUnprocessedAgeMillis());
    status.quarantinePosition()
        .ifPresent(position -> builder.withDetail("quarantinePosition", position));
    return builder.build();
  }
}

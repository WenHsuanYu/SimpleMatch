package com.simplematch.quickfixgateway.matching;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Exposes Gateway final-event quarantine state without failing an otherwise idle market. */
public final class QuickFixFinalMatchingEventsHealthIndicator implements HealthIndicator {
  private final QuickFixFinalMatchingEventStatus status;

  /** Creates a health indicator over the Gateway's compact critical-consumer status. */
  public QuickFixFinalMatchingEventsHealthIndicator(QuickFixFinalMatchingEventStatus status) {
    this.status = status;
  }

  @Override
  public Health health() {
    final Health.Builder builder =
        status.state() == QuickFixFinalMatchingEventStatus.State.READY
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

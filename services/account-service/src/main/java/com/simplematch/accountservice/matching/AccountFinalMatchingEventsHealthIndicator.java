package com.simplematch.accountservice.matching;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Exposes Account final-event readiness without degrading merely because no trades occur. */
public final class AccountFinalMatchingEventsHealthIndicator implements HealthIndicator {
  private final AccountFinalMatchingEventStatus status;

  /** Creates the Account final-event health indicator. */
  public AccountFinalMatchingEventsHealthIndicator(AccountFinalMatchingEventStatus status) {
    this.status = status;
  }

  @Override
  public Health health() {
    final Health.Builder builder =
        status.state() == AccountFinalMatchingEventStatus.State.READY
            ? Health.up()
            : Health.outOfService();
    return builder
        .withDetail("state", status.state().name())
        .withDetail("committedOffsets", status.committedOffsets())
        .withDetail("quarantinePosition", status.quarantinePosition().orElse(null))
        .build();
  }
}

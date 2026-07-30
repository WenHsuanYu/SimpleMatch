package com.simplematch.quickfixgateway.health;

import java.util.function.BooleanSupplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Exposes readiness based on startup recovery completion and QuickFIX acceptor availability. */
public final class QuickFixGatewayReadinessHealthIndicator implements HealthIndicator {
  private final QuickFixGatewayStartupState startupState;
  private final boolean acceptorEnabled;
  private final BooleanSupplier acceptorRunning;

  public QuickFixGatewayReadinessHealthIndicator(
      QuickFixGatewayStartupState startupState,
      boolean acceptorEnabled,
      BooleanSupplier acceptorRunning) {
    this.startupState = startupState;
    this.acceptorEnabled = acceptorEnabled;
    this.acceptorRunning = acceptorRunning;
  }

  @Override
  public Health health() {
    final boolean acceptorIsRunning = acceptorRunning.getAsBoolean();
    final Health.Builder builder;
    if (!startupState.isReady()) {
      builder = Health.outOfService();
    } else if (acceptorEnabled && !acceptorIsRunning) {
      builder = Health.down();
    } else {
      builder = Health.up();
    }

    return builder
        .withDetail("phase", startupState.phase().name())
        .withDetail("replayedWalRecords", startupState.replayedWalRecords())
        .withDetail("acceptorEnabled", acceptorEnabled)
        .withDetail("acceptorRunning", acceptorIsRunning)
        .withDetail("failureMessage", startupState.failureMessage())
        .build();
  }
}

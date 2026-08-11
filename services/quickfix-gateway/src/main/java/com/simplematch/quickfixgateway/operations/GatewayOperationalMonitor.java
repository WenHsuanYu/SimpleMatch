package com.simplematch.quickfixgateway.operations;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Infrastructure scheduler that asks the Gateway domain to apply stale-status and session-close
 * rules.
 */
public final class GatewayOperationalMonitor {
  private final GatewayOperationalController controller;

  /** Creates the scheduled adapter over the single Gateway operational controller. */
  public GatewayOperationalMonitor(GatewayOperationalController controller) {
    this.controller = Objects.requireNonNull(controller, "controller");
  }

  /** Reassesses status freshness and closes the session when the configured cutoff has passed. */
  @Scheduled(
      fixedDelayString = "${simplematch.quickfix-gateway.operations.monitor-interval-millis:1000}")
  public void monitor() {
    controller.monitor();
  }
}

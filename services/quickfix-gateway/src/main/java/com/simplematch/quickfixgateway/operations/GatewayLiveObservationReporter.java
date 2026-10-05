package com.simplematch.quickfixgateway.operations;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Scheduled adapter that publishes only complete live observations to the admission controller. */
public final class GatewayLiveObservationReporter {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(GatewayLiveObservationReporter.class);

  private final TradingSystemObservationCollector collector;
  private final GatewayOperationalController controller;

  /** Creates the fail-closed scheduled reporting boundary. */
  public GatewayLiveObservationReporter(
      TradingSystemObservationCollector collector, GatewayOperationalController controller) {
    this.collector = Objects.requireNonNull(collector, "collector");
    this.controller = Objects.requireNonNull(controller, "controller");
  }

  /** Publishes a complete sample; source failures leave the prior sample to become stale. */
  @Scheduled(
      fixedDelayString =
          "${simplematch.quickfix-gateway.live-observation.interval-millis:1000}")
  public void report() {
    try {
      controller.report(collector.collect());
    } catch (RuntimeException failure) {
      LOGGER.warn("live trading-system observation failed closed", failure);
    }
  }
}

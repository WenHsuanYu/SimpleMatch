package com.simplematch.riskservice.cdc;

import static com.simplematch.config.delivery.DeliveryMetric.CONNECTOR_LAG_EVENTS;
import static com.simplematch.config.delivery.DeliveryMetric.OUTBOX_AGE_MILLIS;

import com.simplematch.config.delivery.DeliveryMetrics;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Refreshes admission safety evidence only after Kafka delivery progress is current. */
public final class CdcDeliveryMonitor {
  private static final String COMPONENT = "risk-cdc-delivery";
  private static final Logger LOGGER = LoggerFactory.getLogger(CdcDeliveryMonitor.class);

  private final CdcDeliveryProgressStore store;
  private final CdcDeliveryProgressProbe probe;
  private final DeliveryMetrics metrics;
  private final String metricName;
  private final String topic;
  private final Clock clock;

  /** Creates the monitor over durable storage, Kafka progress, and telemetry ports. */
  public CdcDeliveryMonitor(
      CdcDeliveryProgressStore store,
      CdcDeliveryProgressProbe probe,
      DeliveryMetrics metrics,
      String metricName,
      String topic,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.probe = Objects.requireNonNull(probe, "probe");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.metricName = requireText(metricName, "metricName");
    this.topic = requireText(topic, "topic");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Refreshes durable lag and metrics, or preserves fail-closed staleness while behind. */
  @Scheduled(
      fixedDelayString =
          "${simplematch.risk-service.cdc-delivery.refresh-interval:5s}")
  public void refresh() {
    final boolean caughtUp;
    try {
      caughtUp = probe.isCaughtUp(topic);
    } catch (RuntimeException failure) {
      LOGGER.atWarn()
          .addKeyValue("event.action", "cdc-delivery-refresh")
          .addKeyValue("event.outcome", "failure")
          .addKeyValue("kafka.topic", topic)
          .setCause(failure)
          .log("CDC delivery metric not refreshed because Kafka progress is unavailable");
      return;
    }
    if (!caughtUp) {
      LOGGER.atWarn()
          .addKeyValue("event.action", "cdc-delivery-refresh")
          .addKeyValue("event.outcome", "deferred")
          .addKeyValue("kafka.topic", topic)
          .log("CDC delivery metric not refreshed because observer group is behind");
      return;
    }
    final CdcDeliverySnapshot snapshot = store.refresh(metricName, topic, clock.millis());
    metrics.observe(CONNECTOR_LAG_EVENTS, COMPONENT, snapshot.lagEvents());
    metrics.observe(OUTBOX_AGE_MILLIS, COMPONENT, snapshot.oldestUndeliveredAgeMillis());
    LOGGER.atInfo()
        .addKeyValue("event.action", "cdc-delivery-refresh")
        .addKeyValue("event.outcome", "success")
        .addKeyValue("kafka.topic", topic)
        .addKeyValue("cdc.lag.events", snapshot.lagEvents())
        .addKeyValue("outbox.oldest.age.ms", snapshot.oldestUndeliveredAgeMillis())
        .log("CDC delivery metric refreshed");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}

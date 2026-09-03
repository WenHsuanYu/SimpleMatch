package com.simplematch.riskservice.cdc;

import static com.simplematch.config.delivery.DeliveryMetric.CONNECTOR_LAG_EVENTS;
import static com.simplematch.config.delivery.DeliveryMetric.OBSERVATION_UPDATED_AT_UNIX_MS;
import static com.simplematch.config.delivery.DeliveryMetric.OUTBOX_AGE_MILLIS;

import com.simplematch.config.delivery.DeliveryMetrics;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** Refreshes admission safety evidence only after Kafka delivery progress is current. */
public final class CdcDeliveryMonitor {
  private static final String COMPONENT = "risk-cdc-delivery";
  private static final Logger LOGGER = LoggerFactory.getLogger(CdcDeliveryMonitor.class);

  private final CdcDeliveryProgressStore store;
  private final CdcDeliveryProgressProbe probe;
  private final DeliveryMetrics metrics;
  private final CdcDeliveryMonitorContext context;
  private final TransactionTemplate transactions;

  /**
   * Creates the monitor over durable storage, Kafka progress, and telemetry ports.
   *
   * @param store durable observation and lag storage
   * @param probe Kafka progress probe for the observer consumer group
   * @param metrics delivery metric sink
   * @param context CDC metric target and durable refresh clock
   * @param transactions transaction boundary owned by this application monitor
   */
  public CdcDeliveryMonitor(
      CdcDeliveryProgressStore store,
      CdcDeliveryProgressProbe probe,
      DeliveryMetrics metrics,
      CdcDeliveryMonitorContext context,
      TransactionTemplate transactions) {
    this.store = Objects.requireNonNull(store, "store");
    this.probe = Objects.requireNonNull(probe, "probe");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.context = Objects.requireNonNull(context, "context");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  /** Refreshes durable lag and metrics, or preserves fail-closed staleness while behind. */
  @Scheduled(
      fixedDelayString =
          "${simplematch.risk-service.cdc-delivery.refresh-interval:5s}")
  public void refresh() {
    final boolean caughtUp;
    try {
      caughtUp = probe.isCaughtUp(context.topic());
    } catch (RuntimeException failure) {
      LOGGER.atWarn()
          .addKeyValue("event.action", "cdc-delivery-refresh")
          .addKeyValue("event.outcome", "failure")
          .addKeyValue("kafka.topic", context.topic())
          .setCause(failure)
          .log("CDC delivery metric not refreshed because Kafka progress is unavailable");
      return;
    }
    if (!caughtUp) {
      LOGGER.atWarn()
          .addKeyValue("event.action", "cdc-delivery-refresh")
          .addKeyValue("event.outcome", "deferred")
          .addKeyValue("kafka.topic", context.topic())
          .log("CDC delivery metric not refreshed because observer group is behind");
      return;
    }
    final long measuredAtUnixMs = context.clock().millis();
    final CdcDeliverySnapshot snapshot =
        requireSnapshot(
            transactions.execute(
                status ->
                    store.refresh(
                        context.metricName(), context.topic(), measuredAtUnixMs)));
    metrics.observe(CONNECTOR_LAG_EVENTS, COMPONENT, snapshot.lagEvents());
    metrics.observe(OUTBOX_AGE_MILLIS, COMPONENT, snapshot.oldestUndeliveredAgeMillis());
    // Publish the timestamp last so a reader can correlate the value gauges with the durable row.
    metrics.observe(OBSERVATION_UPDATED_AT_UNIX_MS, COMPONENT, measuredAtUnixMs);
    LOGGER.atInfo()
        .addKeyValue("event.action", "cdc-delivery-refresh")
        .addKeyValue("event.outcome", "success")
        .addKeyValue("kafka.topic", context.topic())
        .addKeyValue("cdc.lag.events", snapshot.lagEvents())
        .addKeyValue("outbox.oldest.age.ms", snapshot.oldestUndeliveredAgeMillis())
        .log("CDC delivery metric refreshed");
  }

  private static CdcDeliverySnapshot requireSnapshot(CdcDeliverySnapshot snapshot) {
    if (snapshot == null) {
      throw new IllegalStateException("CDC delivery transaction returned no result");
    }
    return snapshot;
  }
}

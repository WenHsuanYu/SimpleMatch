package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.store.JdbcFinalFixDeliveryStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sends durable FIX report intents at least once while keeping QuickFIX session ordering serial.
 */
public final class FinalFixDeliveryDispatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(FinalFixDeliveryDispatcher.class);

  private final JdbcFinalFixDeliveryStore store;
  private final FinalFixExecutionReportMapper reportMapper;
  private final FixSessionMessageSender sender;
  private final OrderSessionRegistry orderSessionRegistry;
  private final Clock clock;
  private final int maximumBatchSize;

  /** Creates the serial dispatcher over durable report intents. */
  public FinalFixDeliveryDispatcher(
      JdbcFinalFixDeliveryStore store,
      FinalFixExecutionReportMapper reportMapper,
      FixSessionMessageSender sender,
      OrderSessionRegistry orderSessionRegistry,
      Clock clock,
      int maximumBatchSize) {
    if (maximumBatchSize <= 0) {
      throw new IllegalArgumentException("maximumBatchSize must be positive");
    }
    this.store = store;
    this.reportMapper = reportMapper;
    this.sender = sender;
    this.orderSessionRegistry = orderSessionRegistry;
    this.clock = clock;
    this.maximumBatchSize = maximumBatchSize;
  }

  /**
   * Retries a bounded ordered batch; an uncertain send intentionally remains pending for replay.
   */
  @Scheduled(
      fixedDelayString =
          "${simplematch.quickfix-gateway.final-matching-events.delivery-retry-delay-millis:1000}")
  public synchronized void dispatchPending() {
    for (FinalFixDeliveryIntent intent : store.findPending(maximumBatchSize)) {
      try {
        sender.send(intent.recipient().sessionId(), reportMapper.render(intent));
        if (store.markSent(intent.identity().deliveryId(), clock.millis())) {
          orderSessionRegistry.recordFinalOrderStatus(
              intent.recipient().orderId().toString(), intent.report().orderStatus());
        }
      } catch (RuntimeException failure) {
        LOGGER.warn(
            "FIX delivery remains pending delivery_id={} event_id={} order_id={}",
            intent.identity().deliveryId(),
            intent.identity().eventId(),
            intent.recipient().orderId(),
            failure);
        return;
      }
    }
  }
}

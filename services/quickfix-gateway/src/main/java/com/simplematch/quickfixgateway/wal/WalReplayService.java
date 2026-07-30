package com.simplematch.quickfixgateway.wal;

import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Re-publishes locally durable gateway commands during owner startup recovery. */
public final class WalReplayService {
  private static final Logger logger = LoggerFactory.getLogger(WalReplayService.class);

  private final WalAppender walAppender;
  private final OrdersCommandPublisher ordersCommandPublisher;

  /** Creates a replay service for the gateway's WAL and compatibility publisher. */
  public WalReplayService(WalAppender walAppender, OrdersCommandPublisher ordersCommandPublisher) {
    this.walAppender = walAppender;
    this.ordersCommandPublisher = ordersCommandPublisher;
  }

  /** Publishes every durable WAL record and returns the number replayed. */
  public int replayAll() {
    int replayed = 0;
    for (WalRecord walRecord : walAppender.readAll()) {
      ordersCommandPublisher.publish(walRecord.toOrderCommand()).join();
      replayed += 1;
    }
    logger.info("replayed {} WAL records from {}", replayed, walAppender.walPath());
    return replayed;
  }
}

package com.simplematch.quickfixgateway.wal;

import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WalReplayService {
    private static final Logger logger = LoggerFactory.getLogger(WalReplayService.class);

    private final WalAppender walAppender;
    private final OrdersCommandPublisher ordersCommandPublisher;

    public WalReplayService(WalAppender walAppender, OrdersCommandPublisher ordersCommandPublisher) {
        this.walAppender = walAppender;
        this.ordersCommandPublisher = ordersCommandPublisher;
    }

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
package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes accepted commands to the optional v1 compatibility stream without delaying FIX. */
final class FixCompatibilityCommandPublisher {
  private static final Logger logger =
      LoggerFactory.getLogger(FixCompatibilityCommandPublisher.class);

  private final OrdersCommandPublisher ordersCommandPublisher;

  FixCompatibilityCommandPublisher(OrdersCommandPublisher ordersCommandPublisher) {
    this.ordersCommandPublisher = ordersCommandPublisher;
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  void publish(OrderCommand command) {
    ordersCommandPublisher
        .publish(command)
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                logger.warn(
                    "orders.commands publish failed for command_id={}",
                    command.getCommandId(),
                    error);
              }
            });
  }
}

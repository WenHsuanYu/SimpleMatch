package com.simplematch.quickfixgateway.fix;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import java.util.concurrent.CompletableFuture;
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

  /**
   * Publishes without waiting; callers may intentionally ignore the returned observation stage.
   *
   * @param command the v1 compatibility command to publish
   * @return the asynchronous publication stage with failure observation attached
   */
  @CanIgnoreReturnValue
  CompletableFuture<Void> publish(OrderCommand command) {
    return ordersCommandPublisher
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

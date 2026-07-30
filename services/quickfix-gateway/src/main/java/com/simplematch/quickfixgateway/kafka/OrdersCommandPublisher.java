package com.simplematch.quickfixgateway.kafka;

import com.simplematch.contracts.orders.v1.OrderCommand;
import java.util.concurrent.CompletableFuture;

/** Publishes gateway compatibility commands to the configured downstream transport. */
public interface OrdersCommandPublisher {
  /** Publishes one command and completes when its transport send has finished. */
  CompletableFuture<Void> publish(OrderCommand command);
}

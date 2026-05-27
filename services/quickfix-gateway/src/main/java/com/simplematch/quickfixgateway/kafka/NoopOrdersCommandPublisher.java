package com.simplematch.quickfixgateway.kafka;

import com.simplematch.contracts.orders.v1.OrderCommand;
import java.util.concurrent.CompletableFuture;

public final class NoopOrdersCommandPublisher implements OrdersCommandPublisher {
  @Override
  public CompletableFuture<Void> publish(OrderCommand command) {
    return CompletableFuture.completedFuture(null);
  }
}
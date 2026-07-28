package com.simplematch.quickfixgateway.kafka;

import com.simplematch.contracts.orders.v1.OrderCommand;

import java.util.concurrent.CompletableFuture;

public interface OrdersCommandPublisher {
    CompletableFuture<Void> publish(OrderCommand command);
}
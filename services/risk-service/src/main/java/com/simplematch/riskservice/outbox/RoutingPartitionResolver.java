package com.simplematch.riskservice.outbox;

@FunctionalInterface
public interface RoutingPartitionResolver {
    int resolve(String symbol);
}
package com.simplematch.riskservice.outbox;

/** Resolves a Kafka partition for a submitted symbol. */
@FunctionalInterface
public interface RoutingPartitionResolver {
  /** Resolves the partition for the supplied symbol. */
  int resolve(String symbol);
}

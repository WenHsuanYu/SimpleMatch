package com.simplematch.marketdataprojection.kafka;

/** Publishes a complete market-data snapshot while preserving its stable event identity and key. */
@FunctionalInterface
public interface MarketDataEventPublisher {
  /** Sends one durable outbox record and returns only after the producer outcome is known. */
  void publish(MarketDataOutboxRecord record);
}

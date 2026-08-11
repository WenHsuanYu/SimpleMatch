package com.simplematch.marketdataprojection.kafka;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka adapter that waits for the producer acknowledgement before marking an outbox record sent.
 */
public final class KafkaMarketDataEventPublisher implements MarketDataEventPublisher {
  private final KafkaTemplate<String, byte[]> kafkaTemplate;

  /** Creates the output adapter over the configured idempotent Spring Kafka producer. */
  public KafkaMarketDataEventPublisher(KafkaTemplate<String, byte[]> kafkaTemplate) {
    this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
  }

  @Override
  public void publish(MarketDataOutboxRecord record) {
    try {
      kafkaTemplate.send(record.topic(), record.key(), record.payload()).get();
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "market-data Kafka publication was interrupted", interruption);
    } catch (ExecutionException failure) {
      throw new IllegalStateException("market-data Kafka publication failed", failure.getCause());
    }
  }
}

package com.simplematch.quickfixgateway.kafka;

import com.simplematch.contracts.orders.v1.OrderCommand;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.core.KafkaTemplate;

/** Publishes compatibility order commands to the configured Kafka topic. */
public final class KafkaOrdersCommandPublisher implements OrdersCommandPublisher {
  private final KafkaTemplate<String, byte[]> kafkaTemplate;
  private final String topic;

  /** Creates a Kafka publisher for the supplied template and destination topic. */
  public KafkaOrdersCommandPublisher(KafkaTemplate<String, byte[]> kafkaTemplate, String topic) {
    this.kafkaTemplate = kafkaTemplate;
    this.topic = topic;
  }

  @Override
  public CompletableFuture<Void> publish(OrderCommand command) {
    final String key = command.getSymbol().isBlank() ? command.getOrderId() : command.getSymbol();
    return kafkaTemplate.send(topic, key, command.toByteArray()).thenApply(result -> null);
  }
}

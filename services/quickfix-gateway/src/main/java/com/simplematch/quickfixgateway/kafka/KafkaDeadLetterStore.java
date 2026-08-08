package com.simplematch.quickfixgateway.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplematch.config.delivery.DeadLetterEvidence;
import com.simplematch.config.delivery.DeadLetterStore;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.springframework.kafka.core.KafkaTemplate;

/** Publishes rebuildable projection diagnostics to a dedicated Kafka dead-letter topic. */
public final class KafkaDeadLetterStore implements DeadLetterStore {
  private final KafkaTemplate<String, byte[]> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final String topic;

  /** Creates a dead-letter store that retains the complete delivery diagnostic context. */
  public KafkaDeadLetterStore(
      KafkaTemplate<String, byte[]> kafkaTemplate, ObjectMapper objectMapper, String topic) {
    this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafka template");
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("dead-letter topic must not be blank");
    }
    this.topic = topic;
  }

  @Override
  public void save(DeadLetterEvidence evidence) {
    Objects.requireNonNull(evidence, "dead-letter evidence");
    try {
      kafkaTemplate
          .send(topic, evidence.record().eventId(), serialize(evidence))
          .get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("dead-letter publication was interrupted", exception);
    } catch (ExecutionException exception) {
      throw new IllegalStateException("dead-letter publication failed", exception.getCause());
    }
  }

  private byte[] serialize(DeadLetterEvidence evidence) {
    final ObjectNode root = objectMapper.createObjectNode();
    root.put("consumer_name", evidence.consumerName());
    root.put("event_id", evidence.record().eventId());
    root.put("topic", evidence.record().position().topic());
    root.put("partition", evidence.record().position().partition());
    root.put("offset", evidence.record().position().offset());
    root.put("payload_base64", Base64.getEncoder().encodeToString(evidence.record().payload()));
    root.put("attempts", evidence.attempts());
    root.put("reason", evidence.reason());
    root.put("dead_lettered_at", evidence.deadLetteredAt().toString());
    try {
      return objectMapper.writeValueAsBytes(root);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("dead-letter diagnostic serialization failed", exception);
    }
  }
}

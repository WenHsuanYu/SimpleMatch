package com.simplematch.config.delivery;

import java.util.Objects;

/** Identifies one immutable position in a Kafka partition. */
public record DeliveryPosition(String topic, int partition, long offset) {
  /** Requires a nonblank topic and a non-negative partition position. */
  public DeliveryPosition {
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("delivery topic must not be blank");
    }
    if (partition < 0 || offset < 0) {
      throw new IllegalArgumentException("delivery partition and offset must be non-negative");
    }
  }

  /** Returns the topic/partition identity used for ordering and pause state. */
  public TopicPartition topicPartition() {
    return new TopicPartition(topic, partition);
  }

  /** Identifies a Kafka topic and partition without an offset. */
  public record TopicPartition(String topic, int partition) {
    /** Requires a nonblank topic and a non-negative partition. */
    public TopicPartition {
      Objects.requireNonNull(topic, "topic");
      if (topic.isBlank() || partition < 0) {
        throw new IllegalArgumentException("topic and partition must be valid");
      }
    }
  }
}

package com.simplematch.marketdataprojection.kafka;

import java.util.Arrays;
import java.util.Objects;

/** One immutable, replayable market-data publication intent owned by the projection database. */
public final class MarketDataOutboxRecord {
  private final byte[] eventId;
  private final String topic;
  private final String key;
  private final byte[] payload;

  /** Defensively owns the binary event identity and exact Protobuf payload. */
  public MarketDataOutboxRecord(byte[] eventId, String topic, String key, byte[] payload) {
    this.eventId = Objects.requireNonNull(eventId, "eventId").clone();
    this.topic = requireText(topic, "topic");
    this.key = requireText(key, "key");
    this.payload = Objects.requireNonNull(payload, "payload").clone();
    if (this.eventId.length != 32) {
      throw new IllegalArgumentException("market-data event identity must contain 32 bytes");
    }
  }

  /** Returns the stable binary event identity without exposing the owned storage. */
  public byte[] eventId() {
    return eventId.clone();
  }

  /** Returns the configured output topic. */
  public String topic() {
    return topic;
  }

  /** Returns the per-instrument Kafka message key. */
  public String key() {
    return key;
  }

  /** Returns the exact Protobuf payload without exposing the owned storage. */
  public byte[] payload() {
    return payload.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof MarketDataOutboxRecord candidate)) {
      return false;
    }
    return Arrays.equals(eventId, candidate.eventId)
        && topic.equals(candidate.topic)
        && key.equals(candidate.key)
        && Arrays.equals(payload, candidate.payload);
  }

  @Override
  public int hashCode() {
    int result = Arrays.hashCode(eventId);
    result = 31 * result + topic.hashCode();
    result = 31 * result + key.hashCode();
    return 31 * result + Arrays.hashCode(payload);
  }

  private String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}

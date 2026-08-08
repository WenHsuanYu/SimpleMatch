package com.simplematch.marketdatapublisher.publication;

import java.util.Objects;
import java.util.UUID;

/** Binary outbox event that makes a committed snapshot publication externally visible. */
public record SnapshotOutboxRecord(
    EventIdentity eventIdentity,
    Destination destination,
    Payload payload,
    AggregateReference aggregateReference,
    long createdAtUnixMs) {

  /** Validates the grouped values used to persist one snapshot publication event. */
  public SnapshotOutboxRecord {
    Objects.requireNonNull(eventIdentity, "eventIdentity");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(aggregateReference, "aggregateReference");
    if (createdAtUnixMs <= 0) {
      throw new IllegalArgumentException("outbox timestamp must be positive");
    }
  }

  /** Event identity carried by the snapshot publication. */
  public record EventIdentity(UUID eventId) {
    /** Requires a stable event identifier. */
    public EventIdentity {
      Objects.requireNonNull(eventId, "event id is required");
    }
  }

  /** Destination topic, business key, and optional explicit Kafka partition. */
  public record Destination(String topic, String messageKey, Integer kafkaPartitionId) {
    /** Creates a destination that lets Kafka choose the partition from the message key. */
    public Destination(String topic, String messageKey) {
      this(topic, messageKey, null);
    }

    /** Requires a destination topic and business key. */
    public Destination {
      requireText(topic, "topic");
      requireText(messageKey, "message key");
      if (kafkaPartitionId != null && kafkaPartitionId < 0) {
        throw new IllegalArgumentException("kafka partition must be non-negative");
      }
    }
  }

  /** Serialized payload and transport headers for the snapshot publication. */
  public static final class Payload {
    private final byte[] bytes;
    private final String payloadType;
    private final String headersJson;

    /** Validates and defensively owns the serialized payload. */
    public Payload(byte[] bytes, String payloadType, String headersJson) {
      this.bytes = Objects.requireNonNull(bytes, "payload").clone();
      this.payloadType = requireText(payloadType, "payload type");
      this.headersJson = requireText(headersJson, "headers JSON");
    }

    /** Returns a defensive copy of the serialized payload. */
    public byte[] bytes() {
      return bytes.clone();
    }

    /** Returns the stable payload schema identifier. */
    public String payloadType() {
      return payloadType;
    }

    /** Returns the serialized transport headers. */
    public String headersJson() {
      return headersJson;
    }
  }

  /** Aggregate identity associated with the snapshot publication. */
  public record AggregateReference(String aggregateType, String aggregateId) {
    /** Requires an aggregate type and identity. */
    public AggregateReference {
      requireText(aggregateType, "aggregate type");
      requireText(aggregateId, "aggregate id");
    }
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }
}

package com.simplematch.marketdatapublisher.routing;

import java.util.Objects;
import java.util.UUID;

/** Binary outbox event that makes a committed routing policy externally visible. */
public record RoutingPolicyOutboxRecord(
    EventIdentity eventIdentity,
    Destination destination,
    Payload payload,
    AggregateReference aggregateReference,
    long createdAtUnixMs) {
  /** Validates the grouped values used to persist one routing policy event. */
  public RoutingPolicyOutboxRecord {
    Objects.requireNonNull(eventIdentity, "event identity");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(aggregateReference, "aggregate reference");
    if (createdAtUnixMs <= 0) {
      throw new IllegalArgumentException("outbox timestamp must be positive");
    }
  }

  /** Event identity carried by the routing policy publication. */
  public record EventIdentity(UUID eventId) {
    /** Requires a stable event identifier. */
    public EventIdentity {
      Objects.requireNonNull(eventId, "event id");
    }
  }

  /** Destination topic, trading-day key, and explicit Kafka partition. */
  public record Destination(String topic, String messageKey, Integer kafkaPartitionId) {
    /** Creates a destination that lets Kafka choose the partition from the message key. */
    public Destination(String topic, String messageKey) {
      this(topic, messageKey, null);
    }

    /** Requires a destination topic and message key. */
    public Destination {
      requireText(topic, "topic");
      requireText(messageKey, "message key");
      if (kafkaPartitionId != null && kafkaPartitionId < 0) {
        throw new IllegalArgumentException("kafka partition must be non-negative");
      }
    }
  }

  /** Serialized protobuf payload and transport headers. */
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

    /** Returns the generated protobuf descriptor name. */
    public String payloadType() {
      return payloadType;
    }

    /** Returns serialized transport headers. */
    public String headersJson() {
      return headersJson;
    }
  }

  /** Aggregate identity associated with the routing policy publication. */
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

package com.simplematch.accountservice.authority;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable semantic outbox value for one account lifecycle event.
 *
 * @param eventIdentity unique event identity
 * @param destination downstream topic and message key
 * @param payload serialized event bytes and transport metadata
 * @param aggregateReference account aggregate associated with the event
 * @param createdAtUnixMs event creation time in Unix milliseconds
 */
public record AccountLifecycleOutbox(
    EventIdentity eventIdentity,
    Destination destination,
    Payload payload,
    AggregateReference aggregateReference,
    long createdAtUnixMs) {
  /** Requires all semantic groups and a non-negative creation timestamp. */
  public AccountLifecycleOutbox {
    Objects.requireNonNull(eventIdentity, "eventIdentity");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(aggregateReference, "aggregateReference");
    if (createdAtUnixMs < 0) {
      throw new IllegalArgumentException("created_at_unix_ms must be non-negative");
    }
  }

  /**
   * Unique identity of one lifecycle event.
   *
   * @param eventId globally unique event identifier
   */
  public record EventIdentity(UUID eventId) {
    /** Requires an event identifier. */
    public EventIdentity {
      Objects.requireNonNull(eventId, "eventId");
    }
  }

  /**
   * Topic and message key used by the downstream event publisher.
   *
   * @param topic destination topic
   * @param messageKey downstream message key
   */
  public record Destination(String topic, String messageKey) {
    /** Requires nonblank destination values. */
    public Destination {
      topic = requireNonBlank(topic, "topic");
      messageKey = requireNonBlank(messageKey, "message_key");
    }
  }

  /** Serialized event bytes and their transport metadata. */
  public static final class Payload {
    private final byte[] bytes;
    private final String payloadType;
    private final String headersJson;

    /**
     * Creates a payload envelope and takes ownership of a defensive byte copy.
     *
     * @param bytes serialized event bytes
     * @param payloadType serialized event type
     * @param headersJson serialized event headers
     */
    public Payload(byte[] bytes, String payloadType, String headersJson) {
      this.bytes = copyRequired(bytes);
      this.payloadType = requireNonBlank(payloadType, "payload_type");
      this.headersJson = requireNonBlank(headersJson, "headers_json");
    }

    /** Returns a defensive copy of the serialized event bytes. */
    public byte[] bytes() {
      return bytes.clone();
    }

    /** Returns the serialized event type. */
    public String payloadType() {
      return payloadType;
    }

    /** Returns the serialized event headers. */
    public String headersJson() {
      return headersJson;
    }

    private static byte[] copyRequired(byte[] value) {
      Objects.requireNonNull(value, "payload");
      if (value.length == 0) {
        throw new IllegalArgumentException("payload must not be empty");
      }
      return value.clone();
    }
  }

  /**
   * Account aggregate reference associated with the lifecycle event.
   *
   * @param aggregateType aggregate category
   * @param aggregateId aggregate identifier
   */
  public record AggregateReference(String aggregateType, String aggregateId) {
    /** Requires nonblank aggregate identity values. */
    public AggregateReference {
      aggregateType = requireNonBlank(aggregateType, "aggregate_type");
      aggregateId = requireNonBlank(aggregateId, "aggregate_id");
    }
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}

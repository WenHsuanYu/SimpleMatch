package com.simplematch.accountservice.authority;

import java.util.UUID;

/** Binary event row emitted with an authoritative reservation lifecycle mutation. */
public final class AccountLifecycleOutbox {
  private final UUID eventId;
  private final String topic;
  private final String messageKey;
  private final byte[] payload;
  private final String payloadType;
  private final String headersJson;
  private final String aggregateType;
  private final String aggregateId;
  private final long createdAtUnixMs;

  /**
   * Creates an immutable account lifecycle outbox row.
   *
   * @param eventId event identifier
   * @param topic destination topic
   * @param messageKey downstream message key
   * @param payload serialized event bytes
   * @param payloadType serialized event type
   * @param headersJson serialized event headers
   * @param aggregateType aggregate category
   * @param aggregateId aggregate identifier
   * @param createdAtUnixMs UTC creation time
   */
  public AccountLifecycleOutbox(
      UUID eventId,
      String topic,
      String messageKey,
      byte[] payload,
      String payloadType,
      String headersJson,
      String aggregateType,
      String aggregateId,
      long createdAtUnixMs) {
    if (eventId == null || payload == null || payload.length == 0 || createdAtUnixMs < 0) {
      throw new IllegalArgumentException("account lifecycle outbox fields are invalid");
    }
    this.eventId = eventId;
    this.topic = requireText(topic, "topic");
    this.messageKey = requireText(messageKey, "message_key");
    this.payload = payload.clone();
    this.payloadType = requireText(payloadType, "payload_type");
    this.headersJson = requireText(headersJson, "headers_json");
    this.aggregateType = requireText(aggregateType, "aggregate_type");
    this.aggregateId = requireText(aggregateId, "aggregate_id");
    this.createdAtUnixMs = createdAtUnixMs;
  }

  /**
   * Returns the event identifier.
   *
   * @return event identifier
   */
  public UUID eventId() {
    return eventId;
  }

  /**
   * Returns the destination topic.
   *
   * @return destination topic
   */
  public String topic() {
    return topic;
  }

  /**
   * Returns the downstream message key.
   *
   * @return downstream message key
   */
  public String messageKey() {
    return messageKey;
  }

  /**
   * Returns a defensive payload copy.
   *
   * @return serialized event bytes
   */
  public byte[] payload() {
    return payload.clone();
  }

  /**
   * Returns the payload schema type.
   *
   * @return serialized event type
   */
  public String payloadType() {
    return payloadType;
  }

  /**
   * Returns serialized event headers.
   *
   * @return serialized headers
   */
  public String headersJson() {
    return headersJson;
  }

  /**
   * Returns the aggregate category.
   *
   * @return aggregate category
   */
  public String aggregateType() {
    return aggregateType;
  }

  /**
   * Returns the aggregate identifier.
   *
   * @return aggregate identifier
   */
  public String aggregateId() {
    return aggregateId;
  }

  /**
   * Returns the UTC epoch-millisecond creation time.
   *
   * @return creation time
   */
  public long createdAtUnixMs() {
    return createdAtUnixMs;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}

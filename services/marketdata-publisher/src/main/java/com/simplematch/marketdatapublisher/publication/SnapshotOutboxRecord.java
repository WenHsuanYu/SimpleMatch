package com.simplematch.marketdatapublisher.publication;

import java.util.Objects;
import java.util.UUID;

/** Binary outbox event that makes a committed snapshot publication externally visible. */
@SuppressWarnings("PMD.TooManyMethods") // Immutable outbox value exposes required defensive-copy accessors.
public final class SnapshotOutboxRecord {
  private final UUID eventId;
  private final String topic;
  private final String messageKey;
  private final byte[] payload;
  private final String payloadType;
  private final String headersJson;
  private final String aggregateType;
  private final String aggregateId;
  private final long createdAtUnixMs;

  private SnapshotOutboxRecord(Builder builder) {
    this.eventId = Objects.requireNonNull(builder.eventId, "event id is required");
    this.topic = requireText(builder.topic, "topic");
    this.messageKey = requireText(builder.messageKey, "message key");
    this.payload = Objects.requireNonNull(builder.payload, "payload").clone();
    this.payloadType = requireText(builder.payloadType, "payload type");
    this.headersJson = requireText(builder.headersJson, "headers JSON");
    this.aggregateType = requireText(builder.aggregateType, "aggregate type");
    this.aggregateId = requireText(builder.aggregateId, "aggregate id");
    if (builder.createdAtUnixMs <= 0) {
      throw new IllegalArgumentException("outbox timestamp must be positive");
    }
    this.createdAtUnixMs = builder.createdAtUnixMs;
  }

  /**
   * Starts construction of a validated immutable binary outbox record.
   *
   * @return a builder for one immutable outbox record
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the stable event identifier. */
  public UUID eventId() {
    return eventId;
  }

  /** Returns the target event topic. */
  public String topic() {
    return topic;
  }

  /** Returns the business key used by downstream consumers. */
  public String messageKey() {
    return messageKey;
  }

  /** Returns a defensive copy of the serialized event envelope. */
  public byte[] payload() {
    return payload.clone();
  }

  /** Returns the stable payload schema identifier. */
  public String payloadType() {
    return payloadType;
  }

  /** Returns JSON headers for the downstream event router. */
  public String headersJson() {
    return headersJson;
  }

  /** Returns the aggregate category for the event. */
  public String aggregateType() {
    return aggregateType;
  }

  /** Returns the immutable snapshot aggregate identity. */
  public String aggregateId() {
    return aggregateId;
  }

  /** Returns the event creation timestamp in UTC epoch milliseconds. */
  public long createdAtUnixMs() {
    return createdAtUnixMs;
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  /** Collects the fields for one immutable outbox record. */
  public static final class Builder {
    private UUID eventId;
    private String topic;
    private String messageKey;
    private byte[] payload;
    private String payloadType;
    private String headersJson;
    private String aggregateType;
    private String aggregateId;
    private long createdAtUnixMs;

    /**
     * Sets the stable event identifier.
     *
     * @param value stable event identifier
     * @return this builder
     */
    public Builder eventId(UUID value) {
      this.eventId = value;
      return this;
    }

    /**
     * Sets the destination event topic.
     *
     * @param value destination topic
     * @return this builder
     */
    public Builder topic(String value) {
      this.topic = value;
      return this;
    }

    /**
     * Sets the downstream business partitioning key.
     *
     * @param value downstream business key
     * @return this builder
     */
    public Builder messageKey(String value) {
      this.messageKey = value;
      return this;
    }

    /**
     * Sets the serialized event envelope.
     *
     * @param value binary serialized event envelope
     * @return this builder
     * @throws NullPointerException if the payload is absent
     */
    public Builder payload(byte[] value) {
      this.payload = Objects.requireNonNull(value, "payload").clone();
      return this;
    }

    /**
     * Sets the stable payload schema identifier.
     *
     * @param value payload schema identifier
     * @return this builder
     */
    public Builder payloadType(String value) {
      this.payloadType = value;
      return this;
    }

    /**
     * Sets serialized routing headers.
     *
     * @param value JSON routing headers
     * @return this builder
     */
    public Builder headersJson(String value) {
      this.headersJson = value;
      return this;
    }

    /**
     * Sets the aggregate category.
     *
     * @param value aggregate category
     * @return this builder
     */
    public Builder aggregateType(String value) {
      this.aggregateType = value;
      return this;
    }

    /**
     * Sets the immutable aggregate identity.
     *
     * @param value immutable aggregate identity
     * @return this builder
     */
    public Builder aggregateId(String value) {
      this.aggregateId = value;
      return this;
    }

    /**
     * Sets the UTC epoch-millisecond creation time.
     *
     * @param value positive UTC epoch-millisecond creation time
     * @return this builder
     */
    public Builder createdAtUnixMs(long value) {
      this.createdAtUnixMs = value;
      return this;
    }

    /**
     * Validates the collected fields and creates the immutable outbox record.
     *
     * @return validated immutable outbox record
     * @throws NullPointerException if a required value is absent
     * @throws IllegalArgumentException if a required string is blank or the timestamp is not positive
     */
    public SnapshotOutboxRecord build() {
      return new SnapshotOutboxRecord(this);
    }
  }
}

package com.simplematch.riskservice.cdc;

import com.simplematch.config.delivery.DeliveryPosition;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Exact Risk outbox event observed after publication to Kafka. */
public record CdcDeliveryObservation(
    UUID eventId,
    DeliveryPosition position,
    String messageKey,
    byte[] payload,
    String payloadType,
    String headersJson,
    long publishedAtUnixMs,
    long observedAtUnixMs) {

  /**
   * Validates the durable Kafka observation and its publication envelope.
   *
   * @param eventId exact Risk outbox event identity
   * @param position exact Kafka delivery position
   * @param messageKey exact Kafka message key
   * @param payload exact Kafka payload bytes
   * @param payloadType exact Debezium event type header
   * @param headersJson exact serialized outbox headers
   * @param publishedAtUnixMs publication timestamp carried by Kafka
   * @param observedAtUnixMs observation timestamp
   */
  public CdcDeliveryObservation {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(position, "position");
    if (messageKey == null || messageKey.isBlank()) {
      throw new IllegalArgumentException("messageKey must not be blank");
    }
    Objects.requireNonNull(payload, "payload");
    if (payload.length == 0) {
      throw new IllegalArgumentException("payload must not be empty");
    }
    payload = payload.clone();
    if (payloadType == null || payloadType.isBlank()) {
      throw new IllegalArgumentException("payloadType must not be blank");
    }
    if (headersJson == null || headersJson.isBlank()) {
      throw new IllegalArgumentException("headersJson must not be blank");
    }
    if (publishedAtUnixMs < 0) {
      throw new IllegalArgumentException("publishedAtUnixMs must not be negative");
    }
    if (observedAtUnixMs < 0) {
      throw new IllegalArgumentException("observedAtUnixMs must not be negative");
    }
  }

  /** Returns a defensive copy of the observed Kafka payload. */
  @Override
  public byte[] payload() {
    return payload.clone();
  }

  /** Compares payload bytes rather than array identity. */
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof CdcDeliveryObservation observation)) {
      return false;
    }
    return publishedAtUnixMs == observation.publishedAtUnixMs
        && observedAtUnixMs == observation.observedAtUnixMs
        && Objects.equals(eventId, observation.eventId)
        && Objects.equals(position, observation.position)
        && Objects.equals(messageKey, observation.messageKey)
        && Arrays.equals(payload, observation.payload)
        && Objects.equals(payloadType, observation.payloadType)
        && Objects.equals(headersJson, observation.headersJson);
  }

  /** Hashes payload bytes consistently with {@link #equals(Object)}. */
  @Override
  public int hashCode() {
    final int result =
        Objects.hash(
            eventId,
            position,
            messageKey,
            payloadType,
            headersJson,
            publishedAtUnixMs,
            observedAtUnixMs);
    return 31 * result + Arrays.hashCode(payload);
  }
}

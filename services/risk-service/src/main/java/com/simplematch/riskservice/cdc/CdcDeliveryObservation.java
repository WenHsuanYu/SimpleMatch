package com.simplematch.riskservice.cdc;

import com.simplematch.config.delivery.DeliveryPosition;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact Risk outbox event observed after publication to Kafka.
 *
 * <p>The wire envelope and Kafka position are named values so the store can compare the complete
 * immutable event before recording delivery evidence.
 */
public record CdcDeliveryObservation(
    CdcDeliveryEnvelope envelope,
    DeliveryPosition position,
    long observedAtUnixMs) {

  /**
   * Validates the durable Kafka observation and its publication envelope.
   *
   * @param envelope exact Debezium event envelope from the Risk outbox
   * @param position exact Kafka delivery position
   * @param observedAtUnixMs observation timestamp
   */
  public CdcDeliveryObservation {
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(position, "position");
    if (observedAtUnixMs < 0) {
      throw new IllegalArgumentException("observedAtUnixMs must not be negative");
    }
  }

  /** Returns the exact Risk outbox event identity. */
  public UUID eventId() {
    return envelope.eventId();
  }

  /** Returns the exact Kafka message key. */
  public String messageKey() {
    return envelope.messageKey();
  }

  /** Returns a defensive copy of the exact Kafka payload bytes. */
  public byte[] payload() {
    return envelope.payload();
  }

  /** Returns the exact Debezium event type header. */
  public String payloadType() {
    return envelope.payloadType();
  }

  /** Returns the exact serialized outbox headers. */
  public String headersJson() {
    return envelope.headersJson();
  }

  /** Returns the publication timestamp carried by Kafka. */
  public long publishedAtUnixMs() {
    return envelope.publishedAtUnixMs();
  }

}

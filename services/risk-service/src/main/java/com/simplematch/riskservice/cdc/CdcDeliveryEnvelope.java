package com.simplematch.riskservice.cdc;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Immutable Debezium envelope compared with one authoritative Risk outbox row. */
public final class CdcDeliveryEnvelope {
  private final UUID eventId;
  private final String messageKey;
  private final byte[] payload;
  private final String payloadType;
  private final String headersJson;
  private final long publishedAtUnixMs;

  /**
   * Creates an immutable CDC envelope and defensively copies its payload bytes.
   *
   * @param eventId exact Risk outbox event identity
   * @param messageKey exact Kafka message key
   * @param payload exact Kafka payload bytes
   * @param payloadType exact Debezium event type header
   * @param headersJson exact serialized outbox headers
   * @param publishedAtUnixMs publication timestamp carried by Kafka
   */
  public CdcDeliveryEnvelope(
      UUID eventId,
      String messageKey,
      byte[] payload,
      String payloadType,
      String headersJson,
      long publishedAtUnixMs) {
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.messageKey = CdcDeliveryEnvelopeValidator.requireText(messageKey, "messageKey");
    this.payload = CdcDeliveryEnvelopeValidator.copyPayload(payload);
    this.payloadType = CdcDeliveryEnvelopeValidator.requireText(payloadType, "payloadType");
    this.headersJson = CdcDeliveryEnvelopeValidator.requireText(headersJson, "headersJson");
    this.publishedAtUnixMs =
        CdcDeliveryEnvelopeValidator.requireNonNegative(publishedAtUnixMs, "publishedAtUnixMs");
  }

  /** Returns the exact Risk outbox event identity. */
  public UUID eventId() {
    return eventId;
  }

  /** Returns the exact Kafka message key. */
  public String messageKey() {
    return messageKey;
  }

  /** Returns a defensive copy of the exact Kafka payload bytes. */
  public byte[] payload() {
    return payload.clone();
  }

  /** Returns the exact Debezium event type header. */
  public String payloadType() {
    return payloadType;
  }

  /** Returns the exact serialized outbox headers. */
  public String headersJson() {
    return headersJson;
  }

  /** Returns the publication timestamp carried by Kafka. */
  public long publishedAtUnixMs() {
    return publishedAtUnixMs;
  }

  /** Compares payload bytes rather than array identity. */
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof CdcDeliveryEnvelope envelope)) {
      return false;
    }
    return publishedAtUnixMs == envelope.publishedAtUnixMs
        && Objects.equals(eventId, envelope.eventId)
        && Objects.equals(messageKey, envelope.messageKey)
        && Arrays.equals(payload, envelope.payload)
        && Objects.equals(payloadType, envelope.payloadType)
        && Objects.equals(headersJson, envelope.headersJson);
  }

  /** Hashes payload bytes consistently with {@link #equals(Object)}. */
  @Override
  public int hashCode() {
    final int result =
        Objects.hash(eventId, messageKey, payloadType, headersJson, publishedAtUnixMs);
    return 31 * result + Arrays.hashCode(payload);
  }
}

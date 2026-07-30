package com.simplematch.riskservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Reusable base factory for composing {@link OutboxRecord} instances across aggregates.
 *
 * @param <T> the source type used by the concrete factory
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractOutboxEventFactory<T> implements OutboxEventFactory<T> {
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull String contentType;

  @Override
  public final OutboxRecord create(T source) {
    final T resolvedSource = Objects.requireNonNull(source, "source");
    final OutboxEvent event = Objects.requireNonNull(buildEvent(resolvedSource), "event");

    return OutboxRecord.create(
        new OutboxRecord.EventInfo(event.eventId(), event.createdAtUnixMs()),
        OutboxRecord.Routing.of(event.topic(), event.messageKey(), event.kafkaPartitionId()),
        new OutboxRecord.PayloadEnvelope(
            event.payload(),
            event.payloadType(),
            headersJson(event.eventId(), event.payloadType())),
        new OutboxRecord.AggregateRef(event.aggregateType(), event.aggregateId()));
  }

  /**
   * Builds the aggregate-specific event envelope used to create the outbox record.
   *
   * @param source the source input
   * @return the envelope that describes the outbox event
   */
  protected abstract OutboxEvent buildEvent(T source);

  private String headersJson(String eventId, String payloadType) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "event_id", eventId,
              "content_type", contentType,
              "payload_type", payloadType));
    } catch (JsonProcessingException jsonProcessingException) {
      throw new IllegalStateException(
          "failed to serialize outbox headers", jsonProcessingException);
    }
  }

  /** Value object that captures all fields required to construct an outbox row. */
  protected static final class OutboxEvent {
    private final String eventId;
    private final long createdAtUnixMs;
    private final String topic;
    private final String messageKey;
    private final Integer kafkaPartitionId;
    private final byte[] payload;
    private final String payloadType;
    private final String aggregateType;
    private final String aggregateId;

    OutboxEvent(
        String eventId,
        long createdAtUnixMs,
        String topic,
        String messageKey,
        Integer kafkaPartitionId,
        byte[] payload,
        String payloadType,
        String aggregateType,
        String aggregateId) {
      this.eventId = eventId;
      this.createdAtUnixMs = createdAtUnixMs;
      this.topic = topic;
      this.messageKey = messageKey;
      this.kafkaPartitionId = kafkaPartitionId;
      this.payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
      this.payloadType = payloadType;
      this.aggregateType = aggregateType;
      this.aggregateId = aggregateId;
    }

    String eventId() {
      return eventId;
    }

    long createdAtUnixMs() {
      return createdAtUnixMs;
    }

    String topic() {
      return topic;
    }

    String messageKey() {
      return messageKey;
    }

    Integer kafkaPartitionId() {
      return kafkaPartitionId;
    }

    byte[] payload() {
      return Arrays.copyOf(payload, payload.length);
    }

    String payloadType() {
      return payloadType;
    }

    String aggregateType() {
      return aggregateType;
    }

    String aggregateId() {
      return aggregateId;
    }
  }
}

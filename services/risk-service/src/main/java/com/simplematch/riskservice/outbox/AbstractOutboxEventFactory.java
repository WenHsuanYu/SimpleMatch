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
    final OutboxRecord.EventInfo eventInfo = event.eventInfo();
    final SerializedPayload payload = event.payload();

    return OutboxRecord.create(
        eventInfo,
        event.routing(),
        new OutboxRecord.PayloadEnvelope(
            payload.bytes(),
            payload.payloadType(),
            headersJson(eventInfo.eventId(), payload.payloadType())),
        event.aggregateReference());
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

  /**
   * Prepared outbox event composed from semantic infrastructure values before headers are added.
   */
  protected static final class OutboxEvent {
    private final OutboxRecord.EventInfo eventInfo;
    private final OutboxRecord.Routing routing;
    private final SerializedPayload payload;
    private final OutboxRecord.AggregateRef aggregateReference;

    /**
     * Creates a prepared event from its identity, route, serialized content, and aggregate owner.
     *
     * @param eventInfo event identity and creation timestamp
     * @param routing destination topic, key, and optional partition
     * @param payload serialized event content before transport headers are added
     * @param aggregateReference aggregate associated with the event
     */
    OutboxEvent(
        OutboxRecord.EventInfo eventInfo,
        OutboxRecord.Routing routing,
        SerializedPayload payload,
        OutboxRecord.AggregateRef aggregateReference) {
      this.eventInfo = Objects.requireNonNull(eventInfo, "eventInfo");
      this.routing = Objects.requireNonNull(routing, "routing");
      this.payload = Objects.requireNonNull(payload, "payload");
      this.aggregateReference = Objects.requireNonNull(aggregateReference, "aggregateReference");
    }

    OutboxRecord.EventInfo eventInfo() {
      return eventInfo;
    }

    OutboxRecord.Routing routing() {
      return routing;
    }

    SerializedPayload payload() {
      return payload;
    }

    OutboxRecord.AggregateRef aggregateReference() {
      return aggregateReference;
    }
  }

  /** Serialized event bytes and their protobuf type before transport headers are added. */
  protected static final class SerializedPayload {
    private final byte[] bytes;
    private final String payloadType;

    /**
     * Creates serialized event content and takes ownership of a defensive byte copy.
     *
     * @param bytes serialized protobuf bytes
     * @param payloadType protobuf message type
     */
    SerializedPayload(byte[] bytes, String payloadType) {
      this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
      this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
    }

    /** Returns a defensive copy of the serialized protobuf bytes. */
    byte[] bytes() {
      return Arrays.copyOf(bytes, bytes.length);
    }

    /** Returns the protobuf message type. */
    String payloadType() {
      return payloadType;
    }
  }
}

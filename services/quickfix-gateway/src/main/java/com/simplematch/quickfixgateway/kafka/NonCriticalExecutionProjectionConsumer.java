package com.simplematch.quickfixgateway.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.DeliveryRecord;
import com.simplematch.config.delivery.NonCriticalDeliveryController;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/** Applies delayed retry and dead-letter policy to the rebuildable QuickFIX projection. */
public final class NonCriticalExecutionProjectionConsumer {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(NonCriticalExecutionProjectionConsumer.class);

  private final ExecutionProjection projection;
  private final NonCriticalDeliveryController deliveryController;
  private final NonCriticalRetryScheduler retryScheduler;

  /** Creates the projection consumer over an isolated non-critical delivery policy. */
  public NonCriticalExecutionProjectionConsumer(
      ExecutionProjection projection,
      NonCriticalDeliveryController deliveryController,
      NonCriticalRetryScheduler retryScheduler) {
    this.projection = Objects.requireNonNull(projection, "projection");
    this.deliveryController = Objects.requireNonNull(deliveryController, "delivery controller");
    this.retryScheduler = Objects.requireNonNull(retryScheduler, "retry scheduler");
  }

  /** Projects one Kafka record and returns so a failed source offset cannot block authority. */
  @KafkaListener(topics = "${simplematch.kafka.topics.matching-executions:matching.executions}")
  public void onExecution(ConsumerRecord<String, byte[]> record) {
    deliver(toDeliveryRecord(record));
  }

  private void deliver(DeliveryRecord record) {
    try {
      projection.project(record.payload());
      deliveryController.onSuccess(record);
    } catch (RuntimeException | InvalidProtocolBufferException failure) {
      final NonCriticalDeliveryController.NonCriticalDeliveryResult result =
          deliveryController.onFailure(record, failure);
      if (result.retryAt() != null) {
        LOGGER.warn(
            "delaying non-critical execution projection topic={} partition={} offset={} until {}",
            record.position().topic(),
            record.position().partition(),
            record.position().offset(),
            result.retryAt(),
            failure);
        retryScheduler.schedule(record, result.retryAt(), () -> deliver(record));
      } else {
        LOGGER.warn(
            "dead-lettered non-critical execution projection topic={} partition={} offset={}",
            record.position().topic(),
            record.position().partition(),
            record.position().offset(),
            failure);
      }
    }
  }

  private DeliveryRecord toDeliveryRecord(ConsumerRecord<String, byte[]> record) {
    Objects.requireNonNull(record, "execution record");
    final byte[] payload = Objects.requireNonNull(record.value(), "execution payload");
    return new DeliveryRecord(
        eventId(record, payload),
        new DeliveryPosition(record.topic(), record.partition(), record.offset()),
        payload);
  }

  private String eventId(ConsumerRecord<String, byte[]> record, byte[] payload) {
    try {
      final String metadataEventId =
          com.simplematch.contracts.matching.v1.ExecutionEvent.parseFrom(payload)
              .getMetadata()
              .getEventId();
      if (!metadataEventId.isBlank()) {
        return metadataEventId;
      }
    } catch (InvalidProtocolBufferException ignored) {
      // Keep the Kafka position as diagnostic identity for malformed payloads.
    }
    if (record.key() != null && !record.key().isBlank()) {
      return record.key();
    }
    return record.topic() + ":" + record.partition() + ":" + record.offset();
  }

  /** Projection operation that may fail without changing authoritative state. */
  @FunctionalInterface
  public interface ExecutionProjection {
    /** Projects one serialized matching execution event. */
    void project(byte[] payload) throws InvalidProtocolBufferException;
  }
}

package com.simplematch.quickfixgateway.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryDecision;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.DeliveryRecord;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventTransportValidator;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryHandler;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryOutcome;
import com.simplematch.quickfixgateway.matching.QuickFixFinalMatchingEventStatus;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** Commits final-event offsets only after the Gateway durably stores every FIX report intent. */
public final class FinalMatchingEventFixConsumer {
  private static final String CONSUMER_GROUP =
      "${simplematch.quickfix-gateway.final-matching-events."
          + "consumer-group:quickfix-final-matching-events}";
  private static final Logger LOGGER =
      LoggerFactory.getLogger(FinalMatchingEventFixConsumer.class);

  private final FinalMatchingEventFixDeliveryHandler deliveryHandler;
  private final CriticalDeliveryController deliveryController;
  private final QuickFixFinalMatchingEventStatus status;

  /** Creates the strict Kafka seam for Gateway final Matching Event delivery. */
  public FinalMatchingEventFixConsumer(
      FinalMatchingEventFixDeliveryHandler deliveryHandler,
      CriticalDeliveryController deliveryController,
      QuickFixFinalMatchingEventStatus status) {
    this.deliveryHandler = Objects.requireNonNull(deliveryHandler, "deliveryHandler");
    this.deliveryController =
        Objects.requireNonNull(deliveryController, "deliveryController");
    this.status = Objects.requireNonNull(status, "status");
  }

  /** Consumes one final event with in-place retry and no ordinary dead-letter escape path. */
  @KafkaListener(
      topics =
          "${simplematch.quickfix-gateway.final-matching-events.topic:matching.events}",
      groupId = CONSUMER_GROUP,
      autoStartup =
          "${simplematch.quickfix-gateway.final-matching-events.enabled:false}",
      properties =
          "key.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer")
  public void onMatchingEvent(
      ConsumerRecord<byte[], byte[]> record,
      Acknowledgment acknowledgment,
      Consumer<?, ?> consumer) {
    final TopicPartition topicPartition =
        new TopicPartition(record.topic(), record.partition());
    final OptionalLong pausedOffset =
        deliveryController.pausedOffset(
            new DeliveryPosition.TopicPartition(record.topic(), record.partition()));
    if (pausedOffset.isPresent() && pausedOffset.getAsLong() <= record.offset()) {
      seek(consumer, topicPartition, pausedOffset.getAsLong());
      consumer.pause(List.of(topicPartition));
      return;
    }

    status.recordPending(record.partition(), record.offset(), record.timestamp());
    final byte[] payload = record.value() == null ? new byte[0] : record.value();
    DeliveryRecord delivery = opaqueDelivery(record, payload);
    try {
      final FinalMatchingEventEnvelope envelope = FinalMatchingEventEnvelope.parse(payload);
      delivery = finalEventDelivery(record, payload, envelope);
      FinalMatchingEventTransportValidator.requireKafkaRecord(
          record.key(), record.partition(), envelope);
      final FinalMatchingEventFixDeliveryOutcome outcome =
          deliveryHandler.persist(envelope, record.partition(), record.offset());
      if (outcome == FinalMatchingEventFixDeliveryOutcome.DUPLICATE) {
        deliveryController.recordDuplicate(delivery);
      }
      if (deliveryController.onSuccess(delivery) == DeliveryDecision.COMMIT) {
        acknowledgment.acknowledge();
        status.recordCommitted(record.partition(), record.offset());
      } else {
        seek(consumer, topicPartition, delivery.position().offset());
      }
    } catch (RuntimeException | InvalidProtocolBufferException failure) {
      final DeliveryDecision decision = deliveryController.onFailure(delivery, failure);
      LOGGER.warn(
          "Gateway final-event delivery failed topic={} partition={} offset={} decision={}",
          record.topic(),
          record.partition(),
          record.offset(),
          decision,
          failure);
      if (decision == DeliveryDecision.QUARANTINED) {
        status.recordQuarantined(delivery.position());
        consumer.pause(List.of(topicPartition));
        return;
      }
      seek(consumer, topicPartition, blockedOffset(delivery, decision));
    }
  }

  /** Resumes the exact final-event offset after an operator repaired its durable cause. */
  public void resume(DeliveryPosition position, long recoveredAtUnixMs) {
    deliveryController.resume(position, recoveredAtUnixMs);
    status.recordRecovered(position);
  }

  private DeliveryRecord opaqueDelivery(ConsumerRecord<byte[], byte[]> record, byte[] payload) {
    return new DeliveryRecord(
        record.topic() + ":" + record.partition() + ":" + record.offset(),
        new DeliveryPosition(record.topic(), record.partition(), record.offset()),
        payload);
  }

  private DeliveryRecord finalEventDelivery(
      ConsumerRecord<byte[], byte[]> record,
      byte[] payload,
      FinalMatchingEventEnvelope envelope) {
    return new DeliveryRecord(
        envelope.eventIdHex(),
        new DeliveryPosition(record.topic(), record.partition(), record.offset()),
        payload);
  }

  private long blockedOffset(DeliveryRecord delivery, DeliveryDecision decision) {
    if (decision == DeliveryDecision.BLOCKED) {
      return deliveryController
          .pausedOffset(delivery.position().topicPartition())
          .orElse(delivery.position().offset());
    }
    return delivery.position().offset();
  }

  private void seek(Consumer<?, ?> consumer, TopicPartition partition, long offset) {
    consumer.seek(partition, offset);
  }
}

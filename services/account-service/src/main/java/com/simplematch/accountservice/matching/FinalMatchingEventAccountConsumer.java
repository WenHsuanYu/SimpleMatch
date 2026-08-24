package com.simplematch.accountservice.matching;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryDecision;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.DeliveryRecord;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventTransportValidator;
import java.util.List;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** Delivers final Matching Events to Account Authority without skipping failed offsets. */
public final class FinalMatchingEventAccountConsumer {
  private static final String CONSUMER_GROUP =
      "${simplematch.account-service.final-matching-events."
          + "consumer-group:account-final-matching-events}";
  private static final Logger LOGGER =
      LoggerFactory.getLogger(FinalMatchingEventAccountConsumer.class);

  private final FinalMatchingEventAccountHandler accountHandler;
  private final CriticalDeliveryController deliveryController;
  private final AccountFinalMatchingEventStatus status;

  /** Creates Account's critical final-event consumer seam. */
  public FinalMatchingEventAccountConsumer(
      FinalMatchingEventAccountHandler accountHandler,
      CriticalDeliveryController deliveryController,
      AccountFinalMatchingEventStatus status) {
    this.accountHandler = Objects.requireNonNull(accountHandler, "accountHandler");
    this.deliveryController =
        Objects.requireNonNull(deliveryController, "deliveryController");
    this.status = Objects.requireNonNull(status, "status");
  }

  /** Commits one Kafka record after Account's final inbox and authority transaction commits. */
  @KafkaListener(
      topics =
          "${simplematch.account-service.final-matching-events.topic:matching.events}",
      groupId = CONSUMER_GROUP,
      autoStartup =
          "${simplematch.account-service.final-matching-events.enabled:false}",
      properties =
          "key.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer")
  public void onMatchingEvent(
      ConsumerRecord<byte[], byte[]> record,
      Acknowledgment acknowledgment,
      Consumer<?, ?> consumer) {
    final byte[] payload = record.value() == null ? new byte[0] : record.value();
    DeliveryRecord delivery = opaqueDelivery(record, payload);
    final TopicPartition topicPartition =
        new TopicPartition(record.topic(), record.partition());
    try {
      final FinalMatchingEventEnvelope envelope =
          FinalMatchingEventEnvelope.parse(payload);
      delivery = finalEventDelivery(record, payload, envelope);
      FinalMatchingEventTransportValidator.requireKafkaRecord(
          record.key(), record.partition(), envelope);
      final FinalMatchingEventAccountCommand command =
          FinalMatchingEventAccountAdapter.adapt(envelope);
      final FinalMatchingEventAccountOutcome outcome =
          accountHandler.apply(command, record.partition(), record.offset());
      if (outcome == FinalMatchingEventAccountOutcome.DUPLICATE) {
        deliveryController.recordDuplicate(delivery);
      }
      if (deliveryController.onSuccess(delivery) == DeliveryDecision.COMMIT) {
        acknowledgment.acknowledge();
        status.recordCommitted(record.partition(), record.offset());
      } else {
        seek(consumer, topicPartition, delivery.position().offset());
      }
    } catch (RuntimeException | InvalidProtocolBufferException failure) {
      final DeliveryDecision decision =
          deliveryController.onFailure(delivery, failure);
      LOGGER.warn(
          "account final-event delivery failed topic={} partition={} offset={} decision={}",
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

  /** Resumes exactly the operator-repaired partition offset. */
  public void resume(DeliveryPosition position, long recoveredAtUnixMs) {
    deliveryController.resume(position, recoveredAtUnixMs);
    status.recordRecovered(position);
  }

  private DeliveryRecord opaqueDelivery(
      ConsumerRecord<byte[], byte[]> record, byte[] payload) {
    return new DeliveryRecord(
        record.topic() + ":" + record.partition() + ":" + record.offset(),
        new DeliveryPosition(
            record.topic(), record.partition(), record.offset()),
        payload);
  }

  private DeliveryRecord finalEventDelivery(
      ConsumerRecord<byte[], byte[]> record,
      byte[] payload,
      FinalMatchingEventEnvelope envelope) {
    return new DeliveryRecord(
        envelope.eventIdHex(),
        new DeliveryPosition(
            record.topic(), record.partition(), record.offset()),
        payload);
  }

  private long blockedOffset(
      DeliveryRecord delivery, DeliveryDecision decision) {
    if (decision == DeliveryDecision.BLOCKED) {
      return deliveryController
          .pausedOffset(delivery.position().topicPartition())
          .orElse(delivery.position().offset());
    }
    return delivery.position().offset();
  }

  private void seek(
      Consumer<?, ?> consumer, TopicPartition partition, long offset) {
    consumer.seek(partition, offset);
  }
}

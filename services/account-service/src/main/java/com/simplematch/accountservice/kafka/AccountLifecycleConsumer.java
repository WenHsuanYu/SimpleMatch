package com.simplematch.accountservice.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryDecision;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.DeliveryRecord;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import java.util.List;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** Applies matching fills and terminal outcomes through the atomic Account Authority boundary. */
public final class AccountLifecycleConsumer {
  private static final Logger LOGGER = LoggerFactory.getLogger(AccountLifecycleConsumer.class);

  private final AccountLifecycleApplier accountService;
  private final CriticalDeliveryController deliveryController;

  /** Creates the Account lifecycle consumer with bounded ordered delivery policy. */
  public AccountLifecycleConsumer(
      AccountLifecycleApplier accountService,
      CriticalDeliveryController deliveryController) {
    this.accountService = Objects.requireNonNull(accountService, "accountService");
    this.deliveryController = Objects.requireNonNull(deliveryController, "deliveryController");
  }

  /** Consumes one matching event without acknowledging failed or quarantined offsets. */
  @KafkaListener(
      topics = "${simplematch.account-service.execution-topic:matching.executions}",
      autoStartup = "${simplematch.account-service.lifecycle-consumer.enabled:false}")
  public void onExecution(
      ConsumerRecord<String, byte[]> record,
      Acknowledgment acknowledgment,
      Consumer<?, ?> consumer) {
    final DeliveryRecord delivery = toDeliveryRecord(record);
    final TopicPartition topicPartition =
        new TopicPartition(record.topic(), record.partition());
    try {
      accountService.applyMatchingExecution(ExecutionEvent.parseFrom(delivery.payload()));
      if (deliveryController.onSuccess(delivery) == DeliveryDecision.COMMIT) {
        acknowledgment.acknowledge();
      } else {
        seek(consumer, topicPartition, delivery.position().offset());
      }
    } catch (RuntimeException | InvalidProtocolBufferException failure) {
      final DeliveryDecision decision = deliveryController.onFailure(delivery, failure);
      LOGGER.warn(
          "account lifecycle delivery failed topic={} partition={} offset={} decision={}",
          record.topic(),
          record.partition(),
          record.offset(),
          decision,
          failure);
      if (decision == DeliveryDecision.QUARANTINED) {
        consumer.pause(List.of(topicPartition));
        return;
      }
      seek(consumer, topicPartition, blockedOffset(delivery, decision));
    }
  }

  /** Resumes the exact quarantined lifecycle event after operator investigation. */
  public void resume(DeliveryPosition position, long recoveredAtUnixMs) {
    deliveryController.resume(position, recoveredAtUnixMs);
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

  private DeliveryRecord toDeliveryRecord(ConsumerRecord<String, byte[]> record) {
    final String eventId =
        record.key() == null || record.key().isBlank()
            ? record.topic() + ":" + record.partition() + ":" + record.offset()
            : record.key();
    return new DeliveryRecord(
        eventId,
        new DeliveryPosition(record.topic(), record.partition(), record.offset()),
        Objects.requireNonNull(record.value(), "matching execution payload"));
  }
}

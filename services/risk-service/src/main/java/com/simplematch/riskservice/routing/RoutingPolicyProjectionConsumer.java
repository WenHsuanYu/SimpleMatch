package com.simplematch.riskservice.routing;

import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryDecision;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.DeliveryRecord;
import java.util.List;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** Consumes Market Reference policy events with same-offset retry and partition quarantine. */
public final class RoutingPolicyProjectionConsumer {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(RoutingPolicyProjectionConsumer.class);

  private final RoutingPolicyProjector projectionService;
  private final CriticalDeliveryController deliveryController;

  /** Creates the critical consumer over the local projection and quarantine policy. */
  public RoutingPolicyProjectionConsumer(
      RoutingPolicyProjector projectionService,
      CriticalDeliveryController deliveryController) {
    this.projectionService = Objects.requireNonNull(projectionService, "projectionService");
    this.deliveryController = Objects.requireNonNull(deliveryController, "deliveryController");
  }

  /** Processes one policy record without acknowledging a failed or quarantined offset. */
  @KafkaListener(
      topics = "${simplematch.risk-service.routing-policy-topic:market-reference.routing-policies}",
      autoStartup = "${simplematch.risk-service.routing-policy-consumer.enabled:false}")
  public void onPolicy(
      ConsumerRecord<String, byte[]> record,
      Acknowledgment acknowledgment,
      Consumer<?, ?> consumer) {
    final DeliveryRecord delivery = toDeliveryRecord(record);
    final TopicPartition topicPartition =
        new TopicPartition(record.topic(), record.partition());
    try {
      projectionService.project(delivery.payload());
      if (deliveryController.onSuccess(delivery) == DeliveryDecision.COMMIT) {
        acknowledgment.acknowledge();
      } else {
        seek(consumer, topicPartition, delivery.position().offset());
      }
    } catch (RuntimeException failure) {
      final DeliveryDecision decision = deliveryController.onFailure(delivery, failure);
      LOGGER.warn(
          "routing policy delivery failed topic={} partition={} offset={} decision={}",
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

  /** Resumes the exact quarantined policy position after investigation. */
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
        Objects.requireNonNull(record.value(), "routing policy payload"));
  }
}

package com.simplematch.marketdataprojection.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.config.delivery.DeliveryDecision;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.DeliveryRecord;
import com.simplematch.config.delivery.NonCriticalDeliveryController;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventTransportValidator;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionHandler;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Delivers final Matching Events to a rebuildable projection without blocking critical consumers.
 */
public final class MarketDataProjectionConsumer {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(MarketDataProjectionConsumer.class);
  private final MarketDataProjectionHandler projectionHandler;
  private final NonCriticalDeliveryController deliveryController;
  private final MarketDataProjectionRetryScheduler retryScheduler;

  /** Creates the non-critical Kafka seam and its independent retry policy. */
  public MarketDataProjectionConsumer(
      MarketDataProjectionHandler projectionHandler,
      NonCriticalDeliveryController deliveryController,
      MarketDataProjectionRetryScheduler retryScheduler) {
    this.projectionHandler =
        Objects.requireNonNull(projectionHandler, "projectionHandler");
    this.deliveryController =
        Objects.requireNonNull(deliveryController, "deliveryController");
    this.retryScheduler =
        Objects.requireNonNull(retryScheduler, "retryScheduler");
  }

  /** Commits after either a successful projection or durable retry/dead-letter handoff. */
  @KafkaListener(
      id = "market-data-projection",
      topics =
          "${simplematch.market-data-projection.matching-events.topic:matching.events}",
      autoStartup =
          "${simplematch.market-data-projection.matching-events.enabled:false}",
      properties =
          "key.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer")
  public void onMatchingEvent(
      ConsumerRecord<byte[], byte[]> record, Acknowledgment acknowledgment) {
    deliverRecord(record);
    acknowledgment.acknowledge();
  }

  private void deliverRecord(ConsumerRecord<byte[], byte[]> record) {
    final DeliveryRecord opaque = opaqueDelivery(record);
    final FinalMatchingEventEnvelope envelope;
    try {
      envelope = FinalMatchingEventEnvelope.parse(opaque.payload());
    } catch (RuntimeException | InvalidProtocolBufferException failure) {
      deferOrDeadLetter(opaque, failure, () -> deliverRecord(record));
      return;
    }
    final DeliveryRecord delivery = finalEventDelivery(record, envelope);
    try {
      FinalMatchingEventTransportValidator.requireKafkaRecord(
          record.key(), record.partition(), envelope);
      deliver(delivery, envelope, () -> deliverRecord(record));
    } catch (RuntimeException failure) {
      deferOrDeadLetter(delivery, failure, () -> deliverRecord(record));
    }
  }

  private void deliver(
      DeliveryRecord delivery,
      FinalMatchingEventEnvelope envelope,
      Runnable retry) {
    try {
      projectionHandler.project(
          envelope,
          delivery.position().partition(),
          delivery.position().offset());
      deliveryController.onSuccess(delivery);
    } catch (RuntimeException failure) {
      deferOrDeadLetter(delivery, failure, retry);
    }
  }

  private void deferOrDeadLetter(
      DeliveryRecord delivery, Throwable failure, Runnable retry) {
    final NonCriticalDeliveryController.NonCriticalDeliveryResult result =
        deliveryController.onFailure(delivery, failure);
    if (result.decision() != DeliveryDecision.COMMIT) {
      throw new IllegalStateException(
          "non-critical delivery must commit after durable handoff");
    }
    if (result.retryAt() == null) {
      LOGGER.warn(
          "dead-lettered market-data projection topic={} partition={} offset={}",
          delivery.position().topic(),
          delivery.position().partition(),
          delivery.position().offset(),
          failure);
      return;
    }
    LOGGER.warn(
        "delaying market-data projection topic={} partition={} offset={} until {}",
        delivery.position().topic(),
        delivery.position().partition(),
        delivery.position().offset(),
        result.retryAt(),
        failure);
    retryScheduler.schedule(delivery, result.retryAt(), retry);
  }

  private DeliveryRecord opaqueDelivery(
      ConsumerRecord<byte[], byte[]> record) {
    final byte[] payload = record.value() == null ? new byte[0] : record.value();
    return new DeliveryRecord(
        record.topic() + ":" + record.partition() + ":" + record.offset(),
        new DeliveryPosition(
            record.topic(), record.partition(), record.offset()),
        payload);
  }

  private DeliveryRecord finalEventDelivery(
      ConsumerRecord<byte[], byte[]> record,
      FinalMatchingEventEnvelope envelope) {
    return new DeliveryRecord(
        envelope.eventIdHex(),
        new DeliveryPosition(
            record.topic(), record.partition(), record.offset()),
        envelope.rawValue());
  }
}

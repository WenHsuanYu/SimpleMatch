package com.simplematch.riskservice.cdc;

import com.simplematch.config.delivery.DeliveryPosition;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** Kafka adapter that correlates Debezium outbox records by their exact {@code id} header. */
public final class KafkaCdcDeliveryListener {
  private final CdcDeliveryProgressStore store;
  private final Clock clock;

  /**
   * Creates the listener over the durable Risk delivery store.
   *
   * @param store durable Risk delivery observation store
   * @param clock clock used for observation timestamps
   * @throws NullPointerException if {@code store} or {@code clock} is {@code null}
   */
  public KafkaCdcDeliveryListener(CdcDeliveryProgressStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Persists one exact observation before acknowledging its Kafka offset.
   *
   * @param record delivered Kafka record carrying the Debezium {@code id} header
   * @param acknowledgment acknowledgement handle for the consumed Kafka offset
   * @throws IllegalArgumentException if the record has no valid Debezium event identity
   */
  @KafkaListener(
      topics = "${simplematch.kafka.topics.matching-commands:matching.commands}",
      groupId = "${simplematch.risk-service.cdc-delivery.consumer-group:risk-cdc-delivery}",
      autoStartup = "${simplematch.risk-service.cdc-delivery.enabled:false}")
  public void onDelivery(
      ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    final Header identity = record.headers().lastHeader("id");
    if (identity == null || identity.value() == null) {
      throw new IllegalArgumentException("Debezium delivery must carry an id header");
    }
    final UUID eventId =
        UUID.fromString(new String(identity.value(), StandardCharsets.UTF_8));
    store.observe(
        new CdcDeliveryObservation(
            eventId,
            new DeliveryPosition(record.topic(), record.partition(), record.offset()),
            clock.millis()));
    acknowledgment.acknowledge();
  }
}

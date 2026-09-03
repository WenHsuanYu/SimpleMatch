package com.simplematch.riskservice.cdc;

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

  /** Creates the listener over the durable Risk delivery store. */
  public KafkaCdcDeliveryListener(CdcDeliveryProgressStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Persists one exact observation before acknowledging its Kafka offset. */
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
            eventId, record.topic(), record.partition(), record.offset(), clock.millis()));
    acknowledgment.acknowledge();
  }
}

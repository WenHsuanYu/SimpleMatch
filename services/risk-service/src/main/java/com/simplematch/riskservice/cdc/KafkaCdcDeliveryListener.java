package com.simplematch.riskservice.cdc;

import com.simplematch.config.delivery.DeliveryPosition;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** Kafka adapter that correlates Debezium outbox records by their exact event metadata. */
public final class KafkaCdcDeliveryListener {
  private static final String EVENT_ID_HEADER = "id";
  private static final String EVENT_TYPE_HEADER = "eventType";
  private static final String HEADERS_JSON_HEADER = "headers_json";
  private static final String FIXTURE_HEADER = "simplematch.fixture";
  private static final byte[] FIXTURE_HEADER_VALUE =
      "matching-kafka-fixture-v1".getBytes(StandardCharsets.UTF_8);

  private final CdcDeliveryProgressStore store;
  private final Clock clock;
  private final boolean fixtureRecordsAllowed;

  /**
   * Creates the listener over the durable Risk delivery store.
   *
   * @param store durable Risk delivery observation store
   * @param clock clock used for observation timestamps
   * @throws NullPointerException if {@code store} or {@code clock} is {@code null}
   */
  public KafkaCdcDeliveryListener(CdcDeliveryProgressStore store, Clock clock) {
    this(store, clock, false);
  }

  /**
   * Creates the listener and optionally permits explicitly marked local fixture records.
   *
   * @param store durable Risk delivery observation store
   * @param clock clock used for observation timestamps
   * @param fixtureRecordsAllowed whether the local-only fixture marker may be acknowledged
   * @throws NullPointerException if {@code store} or {@code clock} is {@code null}
   */
  public KafkaCdcDeliveryListener(
      CdcDeliveryProgressStore store, Clock clock, boolean fixtureRecordsAllowed) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.fixtureRecordsAllowed = fixtureRecordsAllowed;
  }

  /**
   * Persists one exact observation before acknowledging its Kafka offset.
   *
   * @param record delivered Kafka record carrying Debezium outbox metadata
   * @param acknowledgment acknowledgement handle for the consumed Kafka offset
   * @throws IllegalArgumentException if the record has no valid Debezium event identity
   */
  @KafkaListener(
      topics = "${simplematch.kafka.topics.matching-commands:matching.commands}",
      groupId = "${simplematch.risk-service.cdc-delivery.consumer-group:risk-cdc-delivery}",
      autoStartup = "${simplematch.risk-service.cdc-delivery.enabled:false}")
  public void onDelivery(
      ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    Objects.requireNonNull(record, "record");
    Objects.requireNonNull(acknowledgment, "acknowledgment");
    if (isFixtureRecord(record)) {
      acknowledgment.acknowledge();
      return;
    }
    final Header identity = record.headers().lastHeader(EVENT_ID_HEADER);
    if (identity == null || identity.value() == null) {
      throw new IllegalArgumentException("Debezium delivery must carry an id header");
    }
    final UUID eventId = parseEventId(identity.value());
    if (record.key() == null || record.key().isBlank()) {
      throw new IllegalArgumentException("Debezium delivery must carry a message key");
    }
    if (record.value() == null || record.value().length == 0) {
      throw new IllegalArgumentException("Debezium delivery must carry a non-empty payload");
    }
    final String eventType = requiredHeader(record, EVENT_TYPE_HEADER);
    final String headersJson = requiredHeader(record, HEADERS_JSON_HEADER);
    if (record.timestamp() < 0) {
      throw new IllegalArgumentException("Debezium delivery must carry a publication timestamp");
    }
    store.observe(
        new CdcDeliveryObservation(
            eventId,
            new DeliveryPosition(record.topic(), record.partition(), record.offset()),
            record.key(),
            record.value(),
            eventType,
            headersJson,
            record.timestamp(),
            clock.millis()));
    acknowledgment.acknowledge();
  }

  private boolean isFixtureRecord(ConsumerRecord<String, byte[]> record) {
    final Header fixture = record.headers().lastHeader(FIXTURE_HEADER);
    if (fixture == null) {
      return false;
    }
    if (!fixtureRecordsAllowed || !Arrays.equals(fixture.value(), FIXTURE_HEADER_VALUE)) {
      throw new IllegalArgumentException("unapproved CDC fixture marker");
    }
    if (record.headers().lastHeader(EVENT_ID_HEADER) != null) {
      throw new IllegalArgumentException("CDC fixture record must not carry an id header");
    }
    if (record.key() == null || record.key().isBlank()
        || record.value() == null || record.value().length == 0) {
      throw new IllegalArgumentException("CDC fixture record must carry a key and payload");
    }
    return true;
  }

  private static UUID parseEventId(byte[] value) {
    try {
      return UUID.fromString(new String(value, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Debezium delivery id header is not a UUID", exception);
    }
  }

  private static String requiredHeader(ConsumerRecord<String, byte[]> record, String name) {
    final Header header = record.headers().lastHeader(name);
    if (header == null || header.value() == null) {
      throw new IllegalArgumentException("Debezium delivery must carry a " + name + " header");
    }
    final String value = new String(header.value(), StandardCharsets.UTF_8);
    if (value.isBlank()) {
      throw new IllegalArgumentException("Debezium delivery " + name + " header must not be blank");
    }
    return value;
  }
}

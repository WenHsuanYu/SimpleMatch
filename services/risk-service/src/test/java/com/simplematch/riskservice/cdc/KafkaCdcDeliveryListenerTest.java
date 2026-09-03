package com.simplematch.riskservice.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.simplematch.config.delivery.DeliveryPosition;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class KafkaCdcDeliveryListenerTest {
  private static final UUID EVENT_ID =
      UUID.fromString("01990f4a-ff80-7c2c-b71c-33caa9b271d2");
  private static final long PUBLISHED_AT_UNIX_MS = 1_788_307_200_000L;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-02T00:00:05Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("records the Debezium event id before acknowledging")
  void recordsTheDebeziumEventIdHeaderBeforeAcknowledging() {
    final RecordingProgressStore store = new RecordingProgressStore();
    final KafkaCdcDeliveryListener listener = new KafkaCdcDeliveryListener(store, CLOCK);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>(
            "matching.commands", 3, 41L, PUBLISHED_AT_UNIX_MS, "key", new byte[] {1});
    record.headers().add("id", EVENT_ID.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        "eventType",
        "simplematch.matching.runtime.v1.MatchingCommand"
            .getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        "headers_json", "{\"trace-id\":\"cdc-test\"}".getBytes(StandardCharsets.UTF_8));

    listener.onDelivery(record, acknowledgment);

    assertThat(store.observations)
        .containsExactly(
            new CdcDeliveryObservation(
                EVENT_ID,
                new DeliveryPosition("matching.commands", 3, 41L),
                "key",
                new byte[] {1},
                "simplematch.matching.runtime.v1.MatchingCommand",
                "{\"trace-id\":\"cdc-test\"}",
                PUBLISHED_AT_UNIX_MS,
                1788307205000L));
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("rejects a record without the outbox identity")
  void rejectsARecordWithoutTheOutboxIdentityAndDoesNotAcknowledgeIt() {
    final KafkaCdcDeliveryListener listener =
        new KafkaCdcDeliveryListener(new RecordingProgressStore(), CLOCK);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>("matching.commands", 0, 1L, "key", new byte[] {1});

    assertThatThrownBy(() -> listener.onDelivery(record, acknowledgment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id header");
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("acknowledges an explicitly marked local fixture without observing it")
  void acknowledgesAnExplicitlyMarkedLocalFixtureWithoutObservingIt() {
    final RecordingProgressStore store = new RecordingProgressStore();
    final KafkaCdcDeliveryListener listener =
        new KafkaCdcDeliveryListener(store, CLOCK, true);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>("matching.commands", 0, 1L, "fixture-key", new byte[] {1});
    record.headers().add(
        "simplematch.fixture", "matching-kafka-fixture-v1".getBytes(StandardCharsets.UTF_8));

    listener.onDelivery(record, acknowledgment);

    assertThat(store.observations).isEmpty();
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("rejects a fixture marker unless the local opt-in is enabled")
  void rejectsFixtureMarkerUnlessTheLocalOptInIsEnabled() {
    final KafkaCdcDeliveryListener listener =
        new KafkaCdcDeliveryListener(new RecordingProgressStore(), CLOCK);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>("matching.commands", 0, 1L, "fixture-key", new byte[] {1});
    record.headers().add(
        "simplematch.fixture", "matching-kafka-fixture-v1".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> listener.onDelivery(record, acknowledgment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unapproved CDC fixture marker");
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("rejects a marked fixture that also carries a Debezium identity")
  void rejectsMarkedFixtureThatAlsoCarriesDebeziumIdentity() {
    final KafkaCdcDeliveryListener listener =
        new KafkaCdcDeliveryListener(new RecordingProgressStore(), CLOCK, true);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>("matching.commands", 0, 1L, "fixture-key", new byte[] {1});
    record.headers().add(
        "simplematch.fixture", "matching-kafka-fixture-v1".getBytes(StandardCharsets.UTF_8));
    record.headers().add("id", EVENT_ID.toString().getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> listener.onDelivery(record, acknowledgment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry an id header");
    verify(acknowledgment, never()).acknowledge();
  }

  private static final class RecordingProgressStore implements CdcDeliveryProgressStore {
    private final List<CdcDeliveryObservation> observations = new ArrayList<>();

    @Override
    public void observe(CdcDeliveryObservation observation) {
      observations.add(observation);
    }

    @Override
    public CdcDeliverySnapshot refresh(String metricName, String topic, long measuredAtUnixMs) {
      throw new UnsupportedOperationException("not used by this test");
    }
  }
}

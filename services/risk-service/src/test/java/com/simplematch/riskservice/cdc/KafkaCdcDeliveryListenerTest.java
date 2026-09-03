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
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-02T00:00:05Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("records the Debezium event id before acknowledging")
  void recordsTheDebeziumEventIdHeaderBeforeAcknowledging() {
    final RecordingProgressStore store = new RecordingProgressStore();
    final KafkaCdcDeliveryListener listener = new KafkaCdcDeliveryListener(store, CLOCK);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>("matching.commands", 3, 41L, "key", new byte[] {1});
    record.headers().add("id", EVENT_ID.toString().getBytes(StandardCharsets.UTF_8));

    listener.onDelivery(record, acknowledgment);

    assertThat(store.observations)
        .containsExactly(
            new CdcDeliveryObservation(
                EVENT_ID,
                new DeliveryPosition("matching.commands", 3, 41L),
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

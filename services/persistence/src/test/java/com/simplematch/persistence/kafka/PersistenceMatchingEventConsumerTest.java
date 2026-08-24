package com.simplematch.persistence.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.QuarantineEvidence;
import com.simplematch.config.delivery.QuarantineStore;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.persistence.matching.MatchingEventPersistenceOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/** Verifies Persistence consumes the native final-event record before committing Kafka. */
class PersistenceMatchingEventConsumerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final String EVENT_ID_HEX =
      "436c95c15c97744324aaaf0cfd6cd27b371839e944df9ae40ebab37a207cbb6f";
  private static final String PAYLOAD_SHA256 =
      "f263bd42b276005f17556ea3c1fbe5a998c6c1d438521cc5feb1b5c147087a27";
  private static final byte[] EVENT_ID = HexFormat.of().parseHex(EVENT_ID_HEX);

  @Test
  void acknowledgesNativeTradeOnlyAfterPersistenceCompletes() throws IOException {
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> consumer = mockConsumer();
    final PersistenceMatchingEventConsumer matchingConsumer =
        new PersistenceMatchingEventConsumer(
            (envelope, partition, offset) -> {
              assertThat(envelope.event().getEventType())
                  .isEqualTo(MatchingEventType.MATCHING_EVENT_TYPE_TRADE_EXECUTED);
              assertThat(envelope.payloadSha256Hex()).isEqualTo(PAYLOAD_SHA256);
              assertThat(partition).isZero();
              assertThat(offset).isEqualTo(42L);
              return MatchingEventPersistenceOutcome.APPLIED;
            },
            controller(new RecordingQuarantineStore(), 2),
            new PersistenceMatchingEventStatus());

    matchingConsumer.onMatchingEvent(record(EVENT_ID), acknowledgment, consumer);

    verify(acknowledgment).acknowledge();
    verify(consumer, never()).seek(new TopicPartition("matching.events", 0), 42L);
  }

  @Test
  void quarantinesWhenTheKafkaKeyDisagreesWithNativePayloadIdentity() throws IOException {
    final RecordingQuarantineStore quarantines = new RecordingQuarantineStore();
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> consumer = mockConsumer();
    final PersistenceMatchingEventConsumer matchingConsumer =
        new PersistenceMatchingEventConsumer(
            (envelope, partition, offset) -> MatchingEventPersistenceOutcome.APPLIED,
            controller(quarantines, 2),
            new PersistenceMatchingEventStatus());
    final ConsumerRecord<byte[], byte[]> record = record(new byte[32]);
    final TopicPartition topicPartition = new TopicPartition("matching.events", 0);

    matchingConsumer.onMatchingEvent(record, acknowledgment, consumer);
    matchingConsumer.onMatchingEvent(record, acknowledgment, consumer);

    verify(consumer).seek(topicPartition, 42L);
    verify(consumer).pause(List.of(topicPartition));
    verify(acknowledgment, never()).acknowledge();
    assertThat(quarantines.evidence)
        .singleElement()
        .satisfies(evidence -> assertThat(evidence.record().eventId()).isEqualTo(EVENT_ID_HEX));
  }

  private CriticalDeliveryController controller(
      RecordingQuarantineStore quarantines, int attempts) {
    return new CriticalDeliveryController(
        "persistence-matching-events",
        attempts,
        "Correct the durable Persistence state, then resume the same topic partition and offset.",
        CLOCK,
        quarantines);
  }

  private Consumer<?, ?> mockConsumer() {
    return mock(Consumer.class);
  }

  private ConsumerRecord<byte[], byte[]> record(byte[] key) throws IOException {
    return new ConsumerRecord<>("matching.events", 0, 42L, key, nativeTradePayload());
  }

  private byte[] nativeTradePayload() throws IOException {
    try (InputStream stream =
        getClass()
            .getResourceAsStream(
                "/native-routing-fixtures/cpp-matching-trade-executed-v1.hex")) {
      if (stream == null) {
        throw new IOException("missing native TRADE_EXECUTED fixture");
      }
      final String encoded =
          new String(stream.readAllBytes(), StandardCharsets.US_ASCII).replaceAll("\\s", "");
      return HexFormat.of().parseHex(encoded);
    }
  }

  private static final class RecordingQuarantineStore implements QuarantineStore {
    private final List<QuarantineEvidence> evidence = new ArrayList<>();

    @Override
    public void save(QuarantineEvidence quarantine) {
      evidence.add(quarantine);
    }

    @Override
    public void markRecovered(DeliveryPosition position, long recoveredAtUnixMs) {
      // This test only verifies creation of evidence for the exact blocked record.
    }
  }
}

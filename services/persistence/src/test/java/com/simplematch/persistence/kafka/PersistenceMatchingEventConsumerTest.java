package com.simplematch.persistence.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.protobuf.ByteString;
import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.QuarantineEvidence;
import com.simplematch.config.delivery.QuarantineStore;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.persistence.matching.MatchingEventPersistenceOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/** Verifies that Persistence commits Kafka only after its final-event transaction finishes. */
class PersistenceMatchingEventConsumerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final String COMMAND_ID = "0198a001-0000-7000-8000-000000000001";
  private static final byte[] EVENT_ID =
      MatchingEventIdentityV1.eventId(
          "2026-08-11-regular", 0, UUID.fromString(COMMAND_ID), 0);
  private static final String EVENT_ID_HEX = HexFormat.of().formatHex(EVENT_ID);

  @Test
  void acknowledgesOnlyAfterThePersistenceHandlerCompletes() {
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> consumer = mockConsumer();
    final PersistenceMatchingEventConsumer matchingConsumer =
        new PersistenceMatchingEventConsumer(
            (envelope, partition, offset) -> MatchingEventPersistenceOutcome.APPLIED,
            controller(new RecordingQuarantineStore(), 2),
            new PersistenceMatchingEventStatus());

    matchingConsumer.onMatchingEvent(record(EVENT_ID, 9L), acknowledgment, consumer);

    verify(acknowledgment).acknowledge();
    verify(consumer, never()).seek(new TopicPartition("matching.events", 0), 9L);
  }

  @Test
  void quarantinesAndStopsTheExactOffsetWhenTheEventKeyDisagreesWithItsPayloadIdentity() {
    final RecordingQuarantineStore quarantines = new RecordingQuarantineStore();
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> consumer = mockConsumer();
    final PersistenceMatchingEventConsumer matchingConsumer =
        new PersistenceMatchingEventConsumer(
            (envelope, partition, offset) -> MatchingEventPersistenceOutcome.APPLIED,
            controller(quarantines, 2),
            new PersistenceMatchingEventStatus());
    final ConsumerRecord<byte[], byte[]> record = record(new byte[32], 9L);
    final TopicPartition topicPartition = new TopicPartition("matching.events", 0);

    matchingConsumer.onMatchingEvent(record, acknowledgment, consumer);
    matchingConsumer.onMatchingEvent(record, acknowledgment, consumer);

    verify(consumer).seek(topicPartition, 9L);
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

  private ConsumerRecord<byte[], byte[]> record(byte[] key, long offset) {
    return new ConsumerRecord<>("matching.events", 0, offset, key, eventPayload());
  }

  private byte[] eventPayload() {
    return MatchingEvent.newBuilder()
        .setSchemaVersion(1)
        .setIdentityVersion(1)
        .setEventId(ByteString.copyFrom(EVENT_ID))
        .setTradingSessionId("2026-08-11-regular")
        .setPartitionId(0)
        .setSourceCommandId(COMMAND_ID)
        .setSourceInputOffset(9L)
        .setOutputIndex(0)
        .setArtifactIdentity(
            ArtifactIdentity.newBuilder()
                .setTradingDay("2026-08-11")
                .setContentSha256(
                    "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943"))
        .setRoutingAlgorithmVersion("stable-least-loaded-v1")
        .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
        .setOrderRested(
            OrderRested.newBuilder()
                .setOrderId("0198a001-0000-7000-8000-000000000011")
                .setAccountId("0198a001-0000-7000-8000-0000000000aa")
                .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                .setSide(Side.SIDE_BUY)
                .setLeavesQuantityShares(100L)
                .setRestingPriceUnits(1_000_000L))
        .build()
        .toByteArray();
  }

  private static final class RecordingQuarantineStore implements QuarantineStore {
    private final List<QuarantineEvidence> evidence = new ArrayList<>();

    @Override
    public void save(QuarantineEvidence quarantine) {
      evidence.add(quarantine);
    }

    @Override
    public void markRecovered(DeliveryPosition position, long recoveredAtUnixMs) {
      // The consumer test only verifies creation of the exact blocked evidence.
    }
  }
}

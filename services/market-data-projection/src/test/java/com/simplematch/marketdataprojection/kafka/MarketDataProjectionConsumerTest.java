package com.simplematch.marketdataprojection.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.protobuf.ByteString;
import com.simplematch.config.delivery.DeadLetterEvidence;
import com.simplematch.config.delivery.DeadLetterStore;
import com.simplematch.config.delivery.DeliveryRecord;
import com.simplematch.config.delivery.NonCriticalDeliveryController;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionHandler;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/** Verifies market-data delivery retries or dead-letters without blocking matching authority. */
class MarketDataProjectionConsumerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final String COMMAND_ID = "0198a001-0000-7000-8000-000000000001";
  private static final byte[] EVENT_ID =
      MatchingEventIdentityV1.eventId(
          "2026-08-11-regular", 0, UUID.fromString(COMMAND_ID), 0);
  private static final String EVENT_ID_HEX = HexFormat.of().formatHex(EVENT_ID);

  @Test
  void commitsTheSourceRecordThenRetriesTheRebuildableProjection() {
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final RecordingRetryScheduler retries = new RecordingRetryScheduler();
    final RecordingDeadLetterStore deadLetters = new RecordingDeadLetterStore();
    final MarketDataProjectionConsumer consumer =
        new MarketDataProjectionConsumer(
            (envelope, partition, offset) -> {
              throw new IllegalStateException("Redis is temporarily unavailable");
            },
            controller(deadLetters, 2),
            retries);

    consumer.onMatchingEvent(record(EVENT_ID, 7L), acknowledgment);

    verify(acknowledgment).acknowledge();
    assertThat(retries.scheduled).hasSize(1);
    retries.scheduled.getFirst().run();
    assertThat(deadLetters.evidence)
        .singleElement()
        .satisfies(value -> assertThat(value.attempts()).isEqualTo(2));
  }

  @Test
  void routesAnInvalidKeyToTheNonCriticalDeadLetterPathAndCommitsItsSourceOffset() {
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final RecordingDeadLetterStore deadLetters = new RecordingDeadLetterStore();
    final MarketDataProjectionConsumer consumer =
        new MarketDataProjectionConsumer(
            acceptedHandler(), controller(deadLetters, 1), new RecordingRetryScheduler());

    consumer.onMatchingEvent(record(new byte[32], 7L), acknowledgment);

    verify(acknowledgment).acknowledge();
    assertThat(deadLetters.evidence)
        .singleElement()
        .satisfies(value -> assertThat(value.record().eventId()).isEqualTo(EVENT_ID_HEX));
  }

  private MarketDataProjectionHandler acceptedHandler() {
    return (envelope, partition, offset) -> MarketDataProjectionResult.duplicate();
  }

  private NonCriticalDeliveryController controller(
      RecordingDeadLetterStore deadLetters, int attempts) {
    return new NonCriticalDeliveryController(
        "market-data-projection", attempts, Duration.ofSeconds(5), CLOCK, deadLetters);
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
        .setSourceInputOffset(7L)
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

  private static final class RecordingRetryScheduler implements MarketDataProjectionRetryScheduler {
    private final List<Runnable> scheduled = new ArrayList<>();

    @Override
    public void schedule(DeliveryRecord record, Instant retryAt, Runnable retry) {
      scheduled.add(retry);
    }
  }

  private static final class RecordingDeadLetterStore implements DeadLetterStore {
    private final List<DeadLetterEvidence> evidence = new ArrayList<>();

    @Override
    public void save(DeadLetterEvidence value) {
      evidence.add(value);
    }
  }
}

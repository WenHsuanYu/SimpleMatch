package com.simplematch.queryservice.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.queryservice.runtime.QueryProjectionApplicationService;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class QueryProjectionKafkaConsumerTest {
  private static final String COMMAND_ID = "0198a001-0000-7000-8000-000000000001";
  private static final byte[] EVENT_ID =
      MatchingEventIdentityV1.eventId(
          "2026-08-11-regular", 0, UUID.fromString(COMMAND_ID), 0);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void refusesToProjectWhenMatchingKafkaKeyDoesNotEqualEventIdentity() {
    final QueryProjectionApplicationService projectionService =
        mock(QueryProjectionApplicationService.class);
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final QueryProjectionKafkaConsumer consumer =
        new QueryProjectionKafkaConsumer(projectionService, store, CLOCK);

    assertThatThrownBy(
            () ->
                consumer.onMatchingEvent(
                    new ConsumerRecord<>(
                        "matching.events", 0, 7L, new byte[32], eventPayload()),
                    acknowledgment))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(projectionService, store, acknowledgment);
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
}

package com.simplematch.tools.riskmatchinge2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.CancellationReason;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.contracts.matching.runtime.v1.OrderTerminal;
import com.simplematch.contracts.matching.runtime.v1.TradeExecuted;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class MatchingEventObservationMainTest {
  @Test
  void matchesOwningOrderAcrossFinalEventShapes() {
    final String orderId = "0198a000-0000-7000-8000-000000000001";

    final MatchingEvent rested =
        MatchingEvent.newBuilder()
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
            .setOrderRested(OrderRested.newBuilder().setOrderId(orderId))
            .build();
    final MatchingEvent trade =
        MatchingEvent.newBuilder()
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_TRADE_EXECUTED)
            .setTradeExecuted(
                TradeExecuted.newBuilder().setTaker(TradeLeg.newBuilder().setOrderId(orderId)))
            .build();
    final MatchingEvent cancelled =
        MatchingEvent.newBuilder()
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_CANCELLED)
            .setOrderCancelled(OrderTerminal.newBuilder().setOrderId(orderId))
            .build();
    final MatchingEvent expired =
        MatchingEvent.newBuilder()
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_EXPIRED)
            .setOrderExpired(OrderTerminal.newBuilder().setOrderId(orderId))
            .build();

    assertThat(MatchingEventObservationMain.matchesOrder(rested, orderId)).isTrue();
    assertThat(MatchingEventObservationMain.matchesOrder(trade, orderId)).isTrue();
    assertThat(MatchingEventObservationMain.matchesOrder(cancelled, orderId)).isTrue();
    assertThat(MatchingEventObservationMain.matchesOrder(expired, orderId)).isTrue();
    assertThat(
            MatchingEventObservationMain.matchesOrder(
                rested, "0198a000-0000-7000-8000-000000000002"))
        .isFalse();
  }

  @Test
  void carriesConfiguredStartOffsetIntoMatchedObservation() throws Exception {
    final String tradingSessionId = "2026-08-27-regular";
    final int partition = 4;
    final long startOffset = 100L;
    final UUID commandId = UUID.fromString("0198a000-0000-7000-8000-000000000003");
    final String orderId = "0198a000-0000-7000-8000-000000000004";
    final MatchingEvent event = cancelledEvent(tradingSessionId, partition, commandId, orderId);
    final ConsumerRecord<byte[], byte[]> record =
        new ConsumerRecord<>(
            "matching.events", partition, startOffset + 1, null, event.toByteArray());

    final MatchingEventObservationMain.Observation observation =
        MatchingEventObservationMain.matchingObservation(
            record,
            new MatchingEventObservationMain.ObservationArguments(
                "kafka:9092",
                "matching.events",
                partition,
                startOffset,
                commandId.toString(),
                orderId,
                Duration.ofSeconds(5),
                Path.of("build/evidence")));

    assertThat(observation.startOffset()).isEqualTo(startOffset);
    assertThat(observation.offset()).isEqualTo(startOffset + 1);

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    assertThat(json.readTree(json.writeValueAsString(observation)).path("startOffset").asLong())
        .isEqualTo(startOffset);
  }

  private static MatchingEvent cancelledEvent(
      String tradingSessionId, int partition, UUID commandId, String orderId) {
    final String artifactSha256 = "a".repeat(64);
    return MatchingEvent.newBuilder()
        .setSchemaVersion(1)
        .setIdentityVersion(1)
        .setEventId(
            ByteString.copyFrom(
                MatchingEventIdentityV1.eventId(tradingSessionId, partition, commandId, 0)))
        .setTradingSessionId(tradingSessionId)
        .setPartitionId(partition)
        .setSourceCommandId(commandId.toString())
        .setSourceInputOffset(10)
        .setOutputIndex(0)
        .setArtifactIdentity(
            ArtifactIdentity.newBuilder()
                .setTradingDay("2026-08-27")
                .setContentSha256(artifactSha256))
        .setRoutingAlgorithmVersion("stable-least-loaded-v1")
        .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_CANCELLED)
        .setOrderCancelled(
            OrderTerminal.newBuilder()
                .setOrderId(orderId)
                .setAccountId("0198a000-0000-7000-8000-000000000005")
                .setInstrument(
                    VenueInstrument.newBuilder()
                        .setVenueMic("XTAI")
                        .setSymbol("1101"))
                .setSide(Side.SIDE_BUY)
                .setLeavesQuantityShares(1000)
                .setReason(
                    CancellationReason.CANCELLATION_REASON_USER_REQUEST))
        .build();
  }
}

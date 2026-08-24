package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.CommandHeader;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.NewOrder;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.contracts.matching.runtime.v1.TradeLegState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves the final Matching wire seam has one deterministic command and event shape. */
class MatchingRuntimeContractTest {
  private static final ArtifactIdentity ARTIFACT =
      ArtifactIdentity.newBuilder()
          .setTradingDay("2026-08-11")
          .setContentSha256(
              "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943")
          .build();

  @DisplayName("new-order commands retain the daily identity and explicit partition")
  @Test
  void newOrderCommandRoundTrips() throws InvalidProtocolBufferException {
    final MatchingCommand command =
        MatchingCommand.newBuilder()
            .setHeader(
                CommandHeader.newBuilder()
                    .setSchemaVersion(1)
                    .setCommandId("0198a001-0000-7000-8000-000000000001")
                    .setTradingSessionId("2026-08-11-regular")
                    .setPartitionId(0)
                    .setArtifactIdentity(ARTIFACT)
                    .setRoutingAlgorithmVersion("stable-least-loaded-v1"))
            .setNewOrder(
                NewOrder.newBuilder()
                    .setOrderId("0198a001-0000-7000-8000-000000000002")
                    .setAccountId("0198a001-0000-7000-8000-000000000003")
                    .setInstrument(
                        VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setSide(Side.SIDE_BUY)
                    .setQuantityShares(1_000)
                    .setLimitPriceUnits(1_000_000)
                    .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                    .setTimeInForce(TimeInForce.TIME_IN_FORCE_ROD))
            .build();

    final MatchingCommand decoded = MatchingCommand.parseFrom(command.toByteArray());

    assertEquals(command, decoded);
    assertEquals(0, decoded.getHeader().getPartitionId());
    assertEquals(MatchingCommand.CommandCase.NEW_ORDER, decoded.getCommandCase());
  }

  @DisplayName("event identity is a 32-byte deterministic wire value")
  @Test
  void restingEventRoundTripsWithBinaryIdentity() throws InvalidProtocolBufferException {
    final String commandId = "0198a001-0000-7000-8000-000000000001";
    final MatchingEvent event =
        MatchingEvent.newBuilder()
            .setSchemaVersion(1)
            .setIdentityVersion(1)
            .setEventId(
                ByteString.copyFrom(
                    MatchingEventIdentityV1.eventId(
                        "2026-08-11-regular", 0, UUID.fromString(commandId), 0)))
            .setTradingSessionId("2026-08-11-regular")
            .setPartitionId(0)
            .setSourceCommandId(commandId)
            .setSourceInputOffset(42)
            .setOutputIndex(0)
            .setArtifactIdentity(ARTIFACT)
            .setRoutingAlgorithmVersion("stable-least-loaded-v1")
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
            .setOrderRested(
                OrderRested.newBuilder()
                    .setOrderId("0198a001-0000-7000-8000-000000000002")
                    .setAccountId("0198a001-0000-7000-8000-000000000003")
                    .setInstrument(
                        VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setSide(Side.SIDE_BUY)
                    .setLeavesQuantityShares(1_000)
                    .setRestingPriceUnits(1_000_000))
            .build();

    final MatchingEvent decoded = MatchingEvent.parseFrom(event.toByteArray());

    assertEquals(event, decoded);
    assertEquals(32, decoded.getEventId().size());
    assertEquals(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED, decoded.getEventType());
    assertFalse(HexFormat.of().formatHex(event.toByteArray()).isBlank());
  }

  @DisplayName("native trade bytes expose one complete maker and taker transition")
  @Test
  void parsesCppGoldenTradeEvent() throws IOException {
    final byte[] bytes = fixture("cpp-matching-trade-executed-v1.hex");

    final MatchingEvent event = MatchingEvent.parseFrom(bytes);

    assertEquals(1, event.getSchemaVersion());
    assertEquals(1, event.getIdentityVersion());
    assertEquals(42, event.getSourceInputOffset());
    assertEquals(32, event.getEventId().size());
    assertEquals(MatchingEventType.MATCHING_EVENT_TYPE_TRADE_EXECUTED, event.getEventType());
    assertEquals(0, event.getTradeExecuted().getMatchIndex());
    assertEquals(Side.SIDE_BUY, event.getTradeExecuted().getAggressorSide());
    assertEquals(100, event.getTradeExecuted().getQuantityShares());
    assertEquals(1_000_000, event.getTradeExecuted().getPriceUnits());
    assertEquals(
        TradeLegState.TRADE_LEG_STATE_FILLED,
        event.getTradeExecuted().getMaker().getResultingState());
    assertEquals(
        TradeLegState.TRADE_LEG_STATE_FILLED,
        event.getTradeExecuted().getTaker().getResultingState());
  }

  private byte[] fixture(String name) throws IOException {
    try (InputStream stream =
        getClass().getResourceAsStream("/native-routing-fixtures/" + name)) {
      assertTrue(stream != null, "native Matching Event fixture must exist");
      final String encoded =
          new String(stream.readAllBytes(), StandardCharsets.US_ASCII).replaceAll("\\s", "");
      return HexFormat.of().parseHex(encoded);
    }
  }
}

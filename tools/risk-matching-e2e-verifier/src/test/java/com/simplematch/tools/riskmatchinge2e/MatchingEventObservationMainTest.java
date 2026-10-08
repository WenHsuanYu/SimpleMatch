package com.simplematch.tools.riskmatchinge2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.CancellationReason;
import com.simplematch.contracts.matching.runtime.v1.CancelOrder;
import com.simplematch.contracts.matching.runtime.v1.CommandHeader;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
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
                Path.of("build/evidence"), null));

    assertThat(observation.startOffset()).isEqualTo(startOffset);
    assertThat(observation.offset()).isEqualTo(startOffset + 1);
    assertThat(observation.context().sourceInputOffset()).isEqualTo(10);
    assertThat(observation.restedOrder()).isNull();
    assertThat(observation.terminalOrder().leavesQuantityShares()).isEqualTo(1000);
    assertThat(observation.terminalOrder().reason()).isEqualTo("CANCELLATION_REASON_USER_REQUEST");

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    assertThat(json.readTree(json.writeValueAsString(observation)).path("startOffset").asLong())
        .isEqualTo(startOffset);
  }

  @Test
  void observesNewCancelCommandWithoutInventingAnotherReservationOrOrderQuantity() {
    final String commandId = "0198a000-0000-7000-8000-000000000003";
    final var command = MatchingCommand.newBuilder()
        .setHeader(CommandHeader.newBuilder().setCommandId(commandId).setPartitionId(4))
        .setCancelOrder(CancelOrder.newBuilder()
            .setOrderId("0198a000-0000-7000-8000-000000000004")
            .setAccountId("0198a000-0000-7000-8000-000000000005")
            .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("1101"))
            .setSide(Side.SIDE_BUY))
        .build();
    final var observed = new KafkaMatchingCommandProbe.ProbeResult(
        new KafkaMatchingCommandProbe.RecordMetadata(4, 12, 1234, commandId),
        1, new Bytes(command.toByteArray()));
    final var evidence = MatchingEventObservationMain.commandEvidence(observed);
    assertThat(evidence).containsEntry("commandType", "CANCEL_ORDER")
        .containsEntry("orderId", command.getCancelOrder().getOrderId())
        .doesNotContainKeys("quantityShares", "priceUnits", "reservationId", "payloadBase64");
  }

  @Test
  void rejectsLogicalEventPartitionDifferentFromPhysicalKafkaPlacement() {
    final UUID command = UUID.fromString("0198a000-0000-7000-8000-000000000003");
    final String order = "0198a000-0000-7000-8000-000000000004";
    // The envelope and deterministic event ID are internally valid for partition 5.
    final var event = cancelledEvent("2026-08-27-regular", 5, command, order);
    final var arguments = new MatchingEventObservationMain.ObservationArguments(
        "kafka:9092", "matching.events", 4, 0, command.toString(), order,
        Duration.ofSeconds(5), Path.of("build/evidence"), null);
    final var physicallyMisplaced = new ConsumerRecord<byte[], byte[]>(
        "matching.events", 4, 1, event.getEventId().toByteArray(), event.toByteArray());
    assertThatThrownBy(() -> MatchingEventObservationMain.matchingObservation(
        physicallyMisplaced, arguments)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("partition");
  }

  @Test
  void capturesRestedBusinessFactsWithoutRawPayloads() throws Exception {
    final UUID commandId = UUID.fromString("0198a000-0000-7000-8000-000000000003");
    final String orderId = "0198a000-0000-7000-8000-000000000004";
    final MatchingEvent event = cancelledEvent("2026-08-27-regular", 4, commandId, orderId)
        .toBuilder()
        .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
        .setOrderRested(OrderRested.newBuilder()
            .setOrderId(orderId)
            .setAccountId("0198a000-0000-7000-8000-000000000005")
            .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("1101"))
            .setSide(Side.SIDE_BUY)
            .setLeavesQuantityShares(1000)
            .setRestingPriceUnits(569000))
        .build();
    final var arguments = new MatchingEventObservationMain.ObservationArguments(
        "kafka:9092", "matching.events", 4, 0, commandId.toString(), orderId,
        Duration.ofSeconds(5), Path.of("build/evidence"), null);
    final var observation = MatchingEventObservationMain.matchingObservation(
        new ConsumerRecord<>("matching.events", 4, 0, null, event.toByteArray()), arguments);

    assertThat(observation.restedOrder().leavesQuantityShares()).isEqualTo(1000);
    assertThat(observation.restedOrder().restingPriceUnits()).isEqualTo(569000);
    assertThat(observation.restedOrder().accountId()).endsWith("0005");
    assertThat(observation.context().tradingDay()).isEqualTo("2026-08-27");
    final String json = new ObjectMapper().writeValueAsString(observation);
    assertThat(new ObjectMapper().readTree(json).path("restedOrder").path("orderId").asText())
        .isEqualTo(orderId);
    assertThat(json).contains("\"symbol\":\"1101\"").doesNotContain("payloadBase64");
  }

  static MatchingEvent cancelledEvent(
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

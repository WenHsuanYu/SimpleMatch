package com.simplematch.tools.riskmatchinge2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.CommandHeader;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import com.simplematch.contracts.matching.runtime.v1.NewOrder;
import org.junit.jupiter.api.Test;

class MatchingCommandObservationTest {
  private static final String COMMAND_ID = "0198a000-0000-7000-8000-000000000003";

  @Test
  void selectsOnlySafeNewOrderEvidenceAndPhysicalLocation() {
    final MatchingCommand command = command().build();
    final var result = result(command, COMMAND_ID);
    final var evidence = MatchingEventObservationMain.commandEvidence(result);

    assertThat(evidence).containsEntry("commandId", COMMAND_ID)
        .containsEntry("partition", 4).containsEntry("offset", 12L)
        .containsEntry("quantityShares", 1000L).containsEntry("priceUnits", 569000L)
        .containsEntry("side", "SIDE_BUY").containsEntry("physicalDeliveryCount", 2)
        .doesNotContainKeys("payloadBase64", "payload", "headers");
  }

  @Test
  void rejectsPayloadIdentityConflictAndNonOrderCommands() {
    assertThatThrownBy(() -> MatchingEventObservationMain.commandEvidence(result(command().build(), "other-key")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> MatchingEventObservationMain.commandEvidence(result(command().clearNewOrder().build(), COMMAND_ID)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static MatchingCommand.Builder command() {
    return MatchingCommand.newBuilder()
        .setHeader(CommandHeader.newBuilder().setCommandId(COMMAND_ID).setPartitionId(4))
        .setNewOrder(NewOrder.newBuilder()
            .setOrderId("0198a000-0000-7000-8000-000000000004")
            .setAccountId("0198a000-0000-7000-8000-000000000005")
            .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("1101"))
            .setSide(Side.SIDE_BUY).setQuantityShares(1000).setLimitPriceUnits(569000)
            .setOrderType(OrderType.ORDER_TYPE_LIMIT).setTimeInForce(TimeInForce.TIME_IN_FORCE_ROD));
  }

  private static KafkaMatchingCommandProbe.ProbeResult result(MatchingCommand command, String key) {
    return new KafkaMatchingCommandProbe.ProbeResult(
        new KafkaMatchingCommandProbe.RecordMetadata(4, 12, 100, key), 2,
        new Bytes(command.toByteArray()));
  }
}

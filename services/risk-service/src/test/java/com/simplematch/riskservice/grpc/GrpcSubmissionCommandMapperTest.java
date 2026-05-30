package com.simplematch.riskservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.riskservice.submission.ResolvedSubmissionCommand;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GrpcSubmissionCommandMapperTest {
  private final GrpcSubmissionCommandMapper mapper = new GrpcSubmissionCommandMapper();

  @DisplayName("protobuf command maps to a complete domain command")
  @Test
  void mapsProtobufCommandToDomainCommand() {
    final ResolvedSubmissionCommand mapped = mapper.map(newNewOrder(), CommandType.COMMAND_TYPE_NEW);

    assertThat(mapped.payload().commandId()).isEqualTo("cmd-1");
    assertThat(mapped.payload().orderId()).isEqualTo("O-C1");
    assertThat(mapped.payload().accountId()).isEqualTo("ACC-1");
    assertThat(mapped.payload().sessionId()).isEqualTo("FIX.4.4:CLIENT->SIMPLEMATCH");
    assertThat(mapped.payload().tradingDay()).isEqualTo(LocalDate.of(2024, 3, 27));
    assertThat(mapped.payload().clientOrderId()).isEqualTo("C1");
    assertThat(mapped.payload().symbol()).isEqualTo("AAPL");
    assertThat(mapped.payload().side()).isEqualTo(com.simplematch.riskservice.submission.Side.SIDE_BUY);
    assertThat(mapped.payload().quantity()).isEqualTo("10");
    assertThat(mapped.payload().price()).isEqualTo("101.25");
    assertThat(mapped.payload().orderType()).isEqualTo(com.simplematch.riskservice.submission.OrderType.ORDER_TYPE_LIMIT);
    assertThat(mapped.payload().tif()).isEqualTo(com.simplematch.riskservice.submission.TimeInForce.TIME_IN_FORCE_ROD);
    assertThat(mapped.commandType()).isEqualTo(com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_NEW);
    assertThat(mapped.payload().originalClientOrderId()).isEqualTo("");
  }

  @DisplayName("default instance preserves the expected command type")
  @Test
  void normalizesDefaultInstanceToExpectedCommandType() {
    final ResolvedSubmissionCommand mapped = mapper.map(OrderCommand.getDefaultInstance(), CommandType.COMMAND_TYPE_CANCEL);

    assertThat(mapped).isEqualTo(
      ResolvedSubmissionCommand.typedEmpty(
        com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_CANCEL));
  }

  @DisplayName("null command maps to the expected typed empty command")
  @Test
  void mapsNullCommandToExpectedCommandType() {
    final ResolvedSubmissionCommand mapped = mapper.map(null, CommandType.COMMAND_TYPE_CANCEL);

    assertThat(mapped).isEqualTo(
      ResolvedSubmissionCommand.typedEmpty(
            com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_CANCEL));
  }

  private static OrderCommand newNewOrder() {
    return OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId("cmd-1")
          .setCreatedAtUnixMs(1711526950123L)
            .setSourceService("quickfix-gateway")
            .build())
        .setCommandId("cmd-1")
        .setOrderId("O-C1")
        .setAccountId("ACC-1")
        .setSessionId("FIX.4.4:CLIENT->SIMPLEMATCH")
        .setClientOrderId("C1")
        .setSymbol("AAPL")
        .setSide(Side.SIDE_BUY)
        .setQuantity("10")
        .setPrice("101.25")
        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
        .setTif(TimeInForce.TIME_IN_FORCE_ROD)
        .setCommandType(CommandType.COMMAND_TYPE_NEW)
        .build();
  }
}
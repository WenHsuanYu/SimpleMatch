package com.simplematch.riskservice.grpc;

import static com.simplematch.riskservice.testsupport.TestCommandIds.normalize;
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
    final ResolvedSubmissionCommand mapped =
        mapper.map(newNewOrder(), CommandType.COMMAND_TYPE_NEW);

    final var identity = mapped.payload().requestMetadata().identity();
    final var fixIdentity = mapped.payload().requestMetadata().fixIdentity();
    final var order = mapped.payload().orderDetails();

    assertThat(identity.commandId().value()).isEqualTo(normalize("cmd-1"));
    assertThat(identity.orderId().value()).isEqualTo("O-C1");
    assertThat(identity.accountId().value()).isEqualTo("ACC-1");
    assertThat(fixIdentity.senderCompId().value()).isEqualTo("CLIENT");
    assertThat(fixIdentity.targetCompId().value()).isEqualTo("SIMPLEMATCH");
    assertThat(mapped.payload().requestMetadata().tradingDay())
        .isEqualTo(LocalDate.of(2024, 3, 27));
    assertThat(fixIdentity.clOrdId().value()).isEqualTo("C1");
    assertThat(order.symbol()).isEqualTo("AAPL");
    assertThat(order.side())
        .isEqualTo(com.simplematch.riskservice.submission.Side.SIDE_BUY);
    assertThat(order.quantity().value()).isEqualTo("10");
    assertThat(order.price().value()).isEqualTo("101.25");
    assertThat(order.orderType())
        .isEqualTo(com.simplematch.riskservice.submission.OrderType.ORDER_TYPE_LIMIT);
    assertThat(order.tif())
        .isEqualTo(com.simplematch.riskservice.submission.TimeInForce.TIME_IN_FORCE_ROD);
    assertThat(mapped.commandType())
        .isEqualTo(com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_NEW);
    assertThat(fixIdentity.origClOrdId().value()).isEqualTo("");
  }

  @DisplayName("default instance preserves the expected command type")
  @Test
  void normalizesDefaultInstanceToExpectedCommandType() {
    final ResolvedSubmissionCommand mapped =
        mapper.map(OrderCommand.getDefaultInstance(), CommandType.COMMAND_TYPE_CANCEL);

    assertThat(mapped)
        .isEqualTo(
            ResolvedSubmissionCommand.typedEmpty(
                com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_CANCEL));
  }

  @DisplayName("null command maps to the expected typed empty command")
  @Test
  void mapsNullCommandToExpectedCommandType() {
    final ResolvedSubmissionCommand mapped = mapper.map(null, CommandType.COMMAND_TYPE_CANCEL);

    assertThat(mapped)
        .isEqualTo(
            ResolvedSubmissionCommand.typedEmpty(
                com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_CANCEL));
  }

  private static OrderCommand newNewOrder() {
    return OrderCommand.newBuilder()
        .setMetadata(
            EventMetadata.newBuilder()
                .setSchemaVersion("v1")
                .setEventId(normalize("cmd-1"))
                .setCreatedAtUnixMs(1711526950123L)
                .setSourceService("quickfix-gateway")
                .build())
        .setCommandId(normalize("cmd-1"))
        .setOrderId("O-C1")
        .setAccountId("ACC-1")
        .setSenderCompId("CLIENT")
        .setTargetCompId("SIMPLEMATCH")
        .setClOrdId("C1")
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

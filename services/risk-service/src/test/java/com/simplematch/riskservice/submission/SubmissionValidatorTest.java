package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SubmissionValidatorTest {
  private static final long NOW = 1_700_000_000_123L;

  private final SubmissionValidator validator = new SubmissionValidator(
      Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

  @Test
  void returnsAcceptedDecisionForValidNewOrder() {
    final SubmissionDecision decision = validator.evaluate(
        newNewOrder("cmd-1", "O-C1", "C1"),
        CommandType.COMMAND_TYPE_NEW,
        "COMMAND_TYPE_NEW|C1");

    assertThat(decision.submission().accepted()).isTrue();
    assertThat(decision.submission().commandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
    assertThat(decision.submission().createdAtUnixMs()).isEqualTo(NOW);
    assertThat(decision.normalizedCommand().getCommandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
  }

  @Test
  void normalizesUnexpectedCommandTypeToExpectedType() {
    final SubmissionDecision decision = validator.evaluate(
        newNewOrder("cmd-1", "O-C1", "C1").toBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CANCEL)
            .build(),
        CommandType.COMMAND_TYPE_NEW,
        "COMMAND_TYPE_NEW|C1");

    assertThat(decision.submission().accepted()).isTrue();
    assertThat(decision.submission().commandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
    assertThat(decision.normalizedCommand().getCommandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
  }

  @Test
  void rejectsMissingPriceForLimitOrder() {
    final SubmissionDecision decision = validator.evaluate(
        newNewOrder("cmd-1", "O-C1", "C1").toBuilder().clearPrice().build(),
        CommandType.COMMAND_TYPE_NEW,
        "COMMAND_TYPE_NEW|C1");

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("MISSING_PRICE");
  }

  @Test
  void acceptsMarketOrderWithoutPrice() {
    final SubmissionDecision decision = validator.evaluate(
        newNewOrder("cmd-1", "O-C1", "C1").toBuilder()
            .setOrderType(OrderType.ORDER_TYPE_MARKET)
            .clearPrice()
            .build(),
        CommandType.COMMAND_TYPE_NEW,
        "COMMAND_TYPE_NEW|C1");

    assertThat(decision.submission().accepted()).isTrue();
    assertThat(decision.submission().reasonCode()).isEmpty();
    assertThat(decision.submission().reasonText()).isEmpty();
  }

  @Test
  void rejectsCancelWithoutOriginalClientOrderId() {
    final SubmissionDecision decision = validator.evaluate(
        OrderCommand.newBuilder()
            .setMetadata(baseMetadata("cmd-1"))
            .setCommandId("cmd-1")
            .setOrderId("O-C1")
            .setClientOrderId("CXL-1")
            .setCommandType(CommandType.COMMAND_TYPE_CANCEL)
            .build(),
        CommandType.COMMAND_TYPE_CANCEL,
        "COMMAND_TYPE_CANCEL|CXL-1");

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("MISSING_ORIGINAL_CLIENT_ORDER_ID");
  }

  @Test
  void returnsEmptyCommandWhenCommandAndExpectedTypeAreUnspecified() {
    final SubmissionDecision decision = validator.evaluate(
        null,
        CommandType.COMMAND_TYPE_UNSPECIFIED,
        "UNKNOWN|");

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("EMPTY_COMMAND");
    assertThat(decision.submission().commandType()).isEqualTo(CommandType.COMMAND_TYPE_UNSPECIFIED);
    assertThat(decision.normalizedCommand()).isEqualTo(OrderCommand.getDefaultInstance());
  }

  @Test
  void rejectsDefaultCommandForNewOrderAfterNormalization() {
    final SubmissionDecision decision = validator.evaluate(
        OrderCommand.getDefaultInstance(),
        CommandType.COMMAND_TYPE_NEW,
        "COMMAND_TYPE_NEW|");

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("MISSING_CLIENT_ORDER_ID");
    assertThat(decision.normalizedCommand().getCommandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
  }

  private OrderCommand newNewOrder(String commandId, String orderId, String clientOrderId) {
    return OrderCommand.newBuilder()
        .setMetadata(baseMetadata(commandId))
        .setCommandId(commandId)
        .setOrderId(orderId)
        .setAccountId("ACC-1")
        .setSessionId("FIX.4.4:CLIENT->SIMPLEMATCH")
        .setClientOrderId(clientOrderId)
        .setSymbol("AAPL")
        .setSide(Side.SIDE_BUY)
        .setQuantity("10")
        .setPrice("101.25")
        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
        .setTif(TimeInForce.TIME_IN_FORCE_ROD)
        .setCommandType(CommandType.COMMAND_TYPE_NEW)
        .build();
  }

  private EventMetadata baseMetadata(String commandId) {
    return EventMetadata.newBuilder()
        .setSchemaVersion("v1")
        .setEventId(commandId)
        .setCreatedAtUnixMs(1L)
        .setSourceService("quickfix-gateway")
        .build();
  }
}
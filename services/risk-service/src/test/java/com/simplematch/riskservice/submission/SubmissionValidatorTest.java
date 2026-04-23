package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
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
        "COMMAND_TYPE_NEW|C1");

    assertThat(decision.submission().accepted()).isTrue();
    assertThat(decision.submission().commandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
    assertThat(decision.submission().createdAtUnixMs()).isEqualTo(NOW);
    assertThat(decision.command().commandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
  }

  @Test
  void rejectsMissingPriceForLimitOrder() {
    final SubmissionCommand command = new SubmissionCommand(
        "cmd-1",
        "O-C1",
        "ACC-1",
        "FIX.4.4:CLIENT->SIMPLEMATCH",
        "C1",
        "AAPL",
        Side.SIDE_BUY,
        "10",
        "",
        OrderType.ORDER_TYPE_LIMIT,
        TimeInForce.TIME_IN_FORCE_ROD,
        CommandType.COMMAND_TYPE_NEW,
        "");
    final SubmissionDecision decision = validator.evaluate(
        command,
        "COMMAND_TYPE_NEW|C1");

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("MISSING_PRICE");
  }

  @Test
  void acceptsMarketOrderWithoutPrice() {
    final SubmissionCommand command = new SubmissionCommand(
      "cmd-1",
      "O-C1",
      "ACC-1",
      "FIX.4.4:CLIENT->SIMPLEMATCH",
      "C1",
      "AAPL",
      Side.SIDE_BUY,
      "10",
      "",
      OrderType.ORDER_TYPE_MARKET,
      TimeInForce.TIME_IN_FORCE_ROD,
      CommandType.COMMAND_TYPE_NEW,
      "");
    final SubmissionDecision decision = validator.evaluate(
      command,
        "COMMAND_TYPE_NEW|C1");

    assertThat(decision.submission().accepted()).isTrue();
    assertThat(decision.submission().reasonCode()).isEmpty();
    assertThat(decision.submission().reasonText()).isEmpty();
  }

  @Test
  void rejectsCancelWithoutOriginalClientOrderId() {
    final SubmissionDecision decision = validator.evaluate(
      new SubmissionCommand(
        "cmd-1",
        "O-C1",
        "ACC-1",
        "FIX.4.4:CLIENT->SIMPLEMATCH",
        "CXL-1",
        "AAPL",
        Side.SIDE_BUY,
        "10",
        "101.25",
        OrderType.ORDER_TYPE_LIMIT,
        TimeInForce.TIME_IN_FORCE_ROD,
        CommandType.COMMAND_TYPE_CANCEL,
        ""),
        "COMMAND_TYPE_CANCEL|CXL-1");

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("MISSING_ORIGINAL_CLIENT_ORDER_ID");
  }

  @Test
  void returnsEmptyCommandWhenCommandAndExpectedTypeAreUnspecified() {
    final SubmissionDecision decision = validator.evaluate(
        null,
        "UNKNOWN|");

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("EMPTY_COMMAND");
    assertThat(decision.submission().commandType()).isEqualTo(CommandType.COMMAND_TYPE_UNSPECIFIED);
    assertThat(decision.command()).isEqualTo(SubmissionCommand.empty());
  }

  @Test
  void rejectsDefaultCommandForNewOrderAfterNormalization() {
    final SubmissionDecision decision = validator.evaluate(
        SubmissionCommand.empty().withCommandType(CommandType.COMMAND_TYPE_NEW),
        "COMMAND_TYPE_NEW|");

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("MISSING_CLIENT_ORDER_ID");
    assertThat(decision.command().commandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
  }

  private SubmissionCommand newNewOrder(String commandId, String orderId, String clientOrderId) {
    return new SubmissionCommand(
        commandId,
        orderId,
        "ACC-1",
        "FIX.4.4:CLIENT->SIMPLEMATCH",
        clientOrderId,
        "AAPL",
        Side.SIDE_BUY,
        "10",
        "101.25",
        OrderType.ORDER_TYPE_LIMIT,
        TimeInForce.TIME_IN_FORCE_ROD,
        CommandType.COMMAND_TYPE_NEW,
        "");
  }
}
package com.simplematch.riskservice.submission;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import java.time.Clock;
import java.util.Objects;

public final class SubmissionValidator {
  private final Clock clock;

  public SubmissionValidator(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  public SubmissionDecision evaluate(
      OrderCommand command,
      CommandType expectedType,
      String idempotencyKey) {
    final CommandType resolvedExpectedType = expectedType == null
        ? CommandType.COMMAND_TYPE_UNSPECIFIED
        : expectedType;
    final long now = clock.instant().toEpochMilli();

    if ((command == null || OrderCommand.getDefaultInstance().equals(command))
        && resolvedExpectedType == CommandType.COMMAND_TYPE_UNSPECIFIED) {
      return rejected(
          idempotencyKey,
          "",
          "",
          "",
          "",
          CommandType.COMMAND_TYPE_UNSPECIFIED,
          now,
          "EMPTY_COMMAND",
          "risk command payload is required",
          OrderCommand.getDefaultInstance());
    }

    final OrderCommand normalizedCommand = normalize(command, resolvedExpectedType);

    if (normalizedCommand.getClientOrderId().isBlank()) {
      return rejected(
          idempotencyKey,
          normalizedCommand.getCommandId(),
          normalizedCommand.getOrderId(),
          "",
          normalizedCommand.getOriginalClientOrderId(),
          normalizedCommand.getCommandType(),
          now,
          "MISSING_CLIENT_ORDER_ID",
          "client_order_id is required",
          normalizedCommand);
    }
    if (normalizedCommand.getOrderId().isBlank()) {
      return rejected(
          idempotencyKey,
          normalizedCommand.getCommandId(),
          "",
          normalizedCommand.getClientOrderId(),
          normalizedCommand.getOriginalClientOrderId(),
          normalizedCommand.getCommandType(),
          now,
          "MISSING_ORDER_ID",
          "order_id is required",
          normalizedCommand);
    }

    if (normalizedCommand.getCommandType() == CommandType.COMMAND_TYPE_NEW) {
      if (normalizedCommand.getAccountId().isBlank()) {
        return rejected(
            idempotencyKey,
            normalizedCommand.getCommandId(),
            normalizedCommand.getOrderId(),
            normalizedCommand.getClientOrderId(),
            normalizedCommand.getOriginalClientOrderId(),
            normalizedCommand.getCommandType(),
            now,
            "MISSING_ACCOUNT_ID",
            "account_id is required",
            normalizedCommand);
      }
      if (normalizedCommand.getSymbol().isBlank()) {
        return rejected(
            idempotencyKey,
            normalizedCommand.getCommandId(),
            normalizedCommand.getOrderId(),
            normalizedCommand.getClientOrderId(),
            normalizedCommand.getOriginalClientOrderId(),
            normalizedCommand.getCommandType(),
            now,
            "MISSING_SYMBOL",
            "symbol is required",
            normalizedCommand);
      }
      if (normalizedCommand.getQuantity().isBlank()) {
        return rejected(
            idempotencyKey,
            normalizedCommand.getCommandId(),
            normalizedCommand.getOrderId(),
            normalizedCommand.getClientOrderId(),
            normalizedCommand.getOriginalClientOrderId(),
            normalizedCommand.getCommandType(),
            now,
            "MISSING_QUANTITY",
            "quantity is required",
            normalizedCommand);
      }
      if (normalizedCommand.getSide() == Side.SIDE_UNSPECIFIED) {
        return rejected(
            idempotencyKey,
            normalizedCommand.getCommandId(),
            normalizedCommand.getOrderId(),
            normalizedCommand.getClientOrderId(),
            normalizedCommand.getOriginalClientOrderId(),
            normalizedCommand.getCommandType(),
            now,
            "MISSING_SIDE",
            "side is required",
            normalizedCommand);
      }
      if (normalizedCommand.getOrderType() == OrderType.ORDER_TYPE_LIMIT
          && normalizedCommand.getPrice().isBlank()) {
        return rejected(
            idempotencyKey,
            normalizedCommand.getCommandId(),
            normalizedCommand.getOrderId(),
            normalizedCommand.getClientOrderId(),
            normalizedCommand.getOriginalClientOrderId(),
            normalizedCommand.getCommandType(),
            now,
            "MISSING_PRICE",
            "price is required for limit orders",
            normalizedCommand);
      }
    }

    if (normalizedCommand.getCommandType() == CommandType.COMMAND_TYPE_CANCEL
        && normalizedCommand.getOriginalClientOrderId().isBlank()) {
      return rejected(
          idempotencyKey,
          normalizedCommand.getCommandId(),
          normalizedCommand.getOrderId(),
          normalizedCommand.getClientOrderId(),
          "",
          normalizedCommand.getCommandType(),
          now,
          "MISSING_ORIGINAL_CLIENT_ORDER_ID",
          "original_client_order_id is required for cancel requests",
          normalizedCommand);
    }

    return new SubmissionDecision(
        new SubmissionResult(
            idempotencyKey,
            normalizedCommand.getCommandId(),
            normalizedCommand.getOrderId(),
            normalizedCommand.getClientOrderId(),
            normalizedCommand.getOriginalClientOrderId(),
            normalizedCommand.getCommandType(),
            true,
            "",
            "",
            now),
        normalizedCommand);
  }

  private SubmissionDecision rejected(
      String idempotencyKey,
      String requestId,
      String orderId,
      String clientOrderId,
      String originalClientOrderId,
      CommandType commandType,
      long createdAtUnixMs,
      String reasonCode,
      String reasonText,
      OrderCommand normalizedCommand) {
    return new SubmissionDecision(
        new SubmissionResult(
            idempotencyKey,
            requestId,
            orderId,
            clientOrderId,
            originalClientOrderId,
            commandType,
            false,
            reasonCode,
            reasonText,
            createdAtUnixMs),
        normalizedCommand);
  }

  private OrderCommand normalize(OrderCommand command, CommandType expectedType) {
    if (command == null || OrderCommand.getDefaultInstance().equals(command)) {
      if (expectedType == CommandType.COMMAND_TYPE_UNSPECIFIED) {
        return OrderCommand.getDefaultInstance();
      }
      return OrderCommand.newBuilder().setCommandType(expectedType).build();
    }
    if (expectedType != CommandType.COMMAND_TYPE_UNSPECIFIED
        && command.getCommandType() != expectedType) {
      return command.toBuilder().setCommandType(expectedType).build();
    }
    return command;
  }
}
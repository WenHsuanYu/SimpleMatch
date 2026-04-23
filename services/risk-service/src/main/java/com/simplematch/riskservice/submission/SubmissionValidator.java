package com.simplematch.riskservice.submission;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.orders.v1.CommandType;
import java.time.Clock;
import java.util.Objects;

public final class SubmissionValidator {
  private final Clock clock;

  public SubmissionValidator(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  public SubmissionDecision evaluate(
      SubmissionCommand command,
      String idempotencyKey) {
    final SubmissionCommand normalizedCommand = command == null ? SubmissionCommand.empty() : command;
    final long now = clock.instant().toEpochMilli();

    if (normalizedCommand.isEmpty()
        && normalizedCommand.commandType() == CommandType.COMMAND_TYPE_UNSPECIFIED) {
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
              SubmissionCommand.empty());
            }

            if (normalizedCommand.clientOrderId().isBlank()) {
      return rejected(
          idempotencyKey,
              normalizedCommand.commandId(),
              normalizedCommand.orderId(),
          "",
              normalizedCommand.originalClientOrderId(),
              normalizedCommand.commandType(),
          now,
          "MISSING_CLIENT_ORDER_ID",
          "client_order_id is required",
          normalizedCommand);
    }
            if (normalizedCommand.orderId().isBlank()) {
      return rejected(
          idempotencyKey,
              normalizedCommand.commandId(),
          "",
              normalizedCommand.clientOrderId(),
              normalizedCommand.originalClientOrderId(),
              normalizedCommand.commandType(),
          now,
          "MISSING_ORDER_ID",
          "order_id is required",
          normalizedCommand);
    }

            if (normalizedCommand.commandType() == CommandType.COMMAND_TYPE_NEW) {
          if (normalizedCommand.accountId().isBlank()) {
        return rejected(
            idempotencyKey,
            normalizedCommand.commandId(),
            normalizedCommand.orderId(),
            normalizedCommand.clientOrderId(),
            normalizedCommand.originalClientOrderId(),
            normalizedCommand.commandType(),
            now,
            "MISSING_ACCOUNT_ID",
            "account_id is required",
            normalizedCommand);
      }
          if (normalizedCommand.symbol().isBlank()) {
        return rejected(
            idempotencyKey,
            normalizedCommand.commandId(),
            normalizedCommand.orderId(),
            normalizedCommand.clientOrderId(),
            normalizedCommand.originalClientOrderId(),
            normalizedCommand.commandType(),
            now,
            "MISSING_SYMBOL",
            "symbol is required",
            normalizedCommand);
      }
          if (normalizedCommand.quantity().isBlank()) {
        return rejected(
            idempotencyKey,
            normalizedCommand.commandId(),
            normalizedCommand.orderId(),
            normalizedCommand.clientOrderId(),
            normalizedCommand.originalClientOrderId(),
            normalizedCommand.commandType(),
            now,
            "MISSING_QUANTITY",
            "quantity is required",
            normalizedCommand);
      }
          if (normalizedCommand.side() == Side.SIDE_UNSPECIFIED) {
        return rejected(
            idempotencyKey,
            normalizedCommand.commandId(),
            normalizedCommand.orderId(),
            normalizedCommand.clientOrderId(),
            normalizedCommand.originalClientOrderId(),
            normalizedCommand.commandType(),
            now,
            "MISSING_SIDE",
            "side is required",
            normalizedCommand);
      }
          if (normalizedCommand.orderType() == OrderType.ORDER_TYPE_LIMIT
              && normalizedCommand.price().isBlank()) {
        return rejected(
            idempotencyKey,
            normalizedCommand.commandId(),
            normalizedCommand.orderId(),
            normalizedCommand.clientOrderId(),
            normalizedCommand.originalClientOrderId(),
            normalizedCommand.commandType(),
            now,
            "MISSING_PRICE",
            "price is required for limit orders",
            normalizedCommand);
      }
    }

            if (normalizedCommand.commandType() == CommandType.COMMAND_TYPE_CANCEL
            && normalizedCommand.originalClientOrderId().isBlank()) {
      return rejected(
          idempotencyKey,
              normalizedCommand.commandId(),
              normalizedCommand.orderId(),
              normalizedCommand.clientOrderId(),
          "",
              normalizedCommand.commandType(),
          now,
          "MISSING_ORIGINAL_CLIENT_ORDER_ID",
          "original_client_order_id is required for cancel requests",
          normalizedCommand);
    }

    return new SubmissionDecision(
        new SubmissionResult(
            idempotencyKey,
        normalizedCommand.commandId(),
        normalizedCommand.orderId(),
        normalizedCommand.clientOrderId(),
        normalizedCommand.originalClientOrderId(),
        normalizedCommand.commandType(),
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
      SubmissionCommand normalizedCommand) {
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
}
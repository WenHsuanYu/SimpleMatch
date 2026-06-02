package com.simplematch.riskservice.grpc;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.riskservice.submission.CommandType;
import com.simplematch.riskservice.submission.OrderType;
import com.simplematch.riskservice.submission.ResolvedSubmissionCommand;
import com.simplematch.riskservice.submission.Side;
import com.simplematch.riskservice.submission.SubmissionCommand;
import com.simplematch.riskservice.submission.TimeInForce;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

final class GrpcSubmissionCommandMapper {
  ResolvedSubmissionCommand map(
      OrderCommand command,
      com.simplematch.contracts.orders.v1.CommandType expectedType) {
    final CommandType normalizedExpectedType = toCommandType(expectedType);
    final ResolvedSubmissionCommand typedEmptyCommand = ResolvedSubmissionCommand.typedEmpty(normalizedExpectedType);
    if (command == null || OrderCommand.getDefaultInstance().equals(command)) {
      return typedEmptyCommand;
    }

    final SubmissionCommand mappedCommand = SubmissionCommand.create(
      new SubmissionCommand.RequestMetadata(
        command.getCommandId(),
        command.getOrderId(),
        command.getAccountId(),
        command.getSenderCompId(),
        command.getTargetCompId(),
        command.getClOrdId(),
        command.getOrigClOrdId(),
        tradingDayFor(command)),
      new SubmissionCommand.OrderDetails(
        command.getSymbol(),
        toSide(command.getSide()),
        command.getQuantity(),
        command.getPrice(),
        toOrderType(command.getOrderType()),
        toTimeInForce(command.getTif())));
    return new ResolvedSubmissionCommand(mappedCommand, toCommandType(command.getCommandType()))
        .withResolvedCommandType(normalizedExpectedType);
  }

  private static LocalDate tradingDayFor(OrderCommand command) {
    if (command == null || !command.hasMetadata()) {
      return null;
    }
    final long createdAtUnixMs = command.getMetadata().getCreatedAtUnixMs();
    if (createdAtUnixMs <= 0) {
      return null;
    }
    return Instant.ofEpochMilli(createdAtUnixMs).atZone(ZoneOffset.UTC).toLocalDate();
  }

  private static Side toSide(com.simplematch.contracts.common.v1.Side side) {
    if (side == null) {
      return Side.SIDE_UNSPECIFIED;
    }
    return switch (side) {
      case SIDE_BUY -> Side.SIDE_BUY;
      case SIDE_SELL -> Side.SIDE_SELL;
      default -> Side.SIDE_UNSPECIFIED;
    };
  }

  private static OrderType toOrderType(com.simplematch.contracts.common.v1.OrderType orderType) {
    if (orderType == null) {
      return OrderType.ORDER_TYPE_UNSPECIFIED;
    }
    return switch (orderType) {
      case ORDER_TYPE_LIMIT -> OrderType.ORDER_TYPE_LIMIT;
      case ORDER_TYPE_MARKET -> OrderType.ORDER_TYPE_MARKET;
      default -> OrderType.ORDER_TYPE_UNSPECIFIED;
    };
  }

  private static TimeInForce toTimeInForce(com.simplematch.contracts.common.v1.TimeInForce timeInForce) {
    if (timeInForce == null) {
      return TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    }
    return switch (timeInForce) {
      case TIME_IN_FORCE_ROD -> TimeInForce.TIME_IN_FORCE_ROD;
      case TIME_IN_FORCE_IOC -> TimeInForce.TIME_IN_FORCE_IOC;
      case TIME_IN_FORCE_FOK -> TimeInForce.TIME_IN_FORCE_FOK;
      default -> TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    };
  }

  private static CommandType toCommandType(com.simplematch.contracts.orders.v1.CommandType commandType) {
    if (commandType == null) {
      return CommandType.COMMAND_TYPE_UNSPECIFIED;
    }
    return switch (commandType) {
      case COMMAND_TYPE_NEW -> CommandType.COMMAND_TYPE_NEW;
      case COMMAND_TYPE_CANCEL -> CommandType.COMMAND_TYPE_CANCEL;
      default -> CommandType.COMMAND_TYPE_UNSPECIFIED;
    };
  }
}
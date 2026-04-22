package com.simplematch.riskservice.submission;

import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;

public final class SubmissionIdempotencyKeyFactory {
  public String create(OrderCommand command, CommandType expectedType) {
    final CommandType resolvedType = resolveType(command, expectedType);
    if (command == null && resolvedType == CommandType.COMMAND_TYPE_UNSPECIFIED) {
      return "UNKNOWN|";
    }
    return resolvedType.name() + "|" + clientOrderId(command);
  }

  private CommandType resolveType(OrderCommand command, CommandType expectedType) {
    if (expectedType != null && expectedType != CommandType.COMMAND_TYPE_UNSPECIFIED) {
      return expectedType;
    }
    if (command == null || OrderCommand.getDefaultInstance().equals(command)) {
      return CommandType.COMMAND_TYPE_UNSPECIFIED;
    }
    return command.getCommandType();
  }

  private String clientOrderId(OrderCommand command) {
    return command == null ? "" : command.getClientOrderId();
  }
}
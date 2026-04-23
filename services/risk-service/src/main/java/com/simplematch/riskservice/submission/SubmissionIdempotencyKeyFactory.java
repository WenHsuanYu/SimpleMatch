package com.simplematch.riskservice.submission;

import com.simplematch.contracts.orders.v1.CommandType;

public final class SubmissionIdempotencyKeyFactory {
  public String create(SubmissionCommand command) {
    final SubmissionCommand normalizedCommand = command == null ? SubmissionCommand.empty() : command;
    final CommandType resolvedType = normalizedCommand.commandType();
    if (normalizedCommand.isEmpty() && resolvedType == CommandType.COMMAND_TYPE_UNSPECIFIED) {
      return "UNKNOWN|";
    }
    return resolvedType.name() + "|" + normalizedCommand.clientOrderId();
  }
}
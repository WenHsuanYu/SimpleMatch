package com.simplematch.riskservice.submission;

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
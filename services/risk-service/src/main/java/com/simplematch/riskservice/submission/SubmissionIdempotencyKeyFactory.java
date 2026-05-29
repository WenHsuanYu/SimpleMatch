package com.simplematch.riskservice.submission;

public final class SubmissionIdempotencyKeyFactory {
  public String create(ResolvedSubmissionCommand command) {
    final ResolvedSubmissionCommand normalizedCommand = command == null
        ? ResolvedSubmissionCommand.unspecified()
        : command;
    final CommandType resolvedType = normalizedCommand.commandType();
    if (normalizedCommand.isCompletelyUnspecified()) {
      return "UNKNOWN|";
    }
    return resolvedType.name() + "|" + normalizedCommand.payload().clientOrderIdValue().value();
  }
}
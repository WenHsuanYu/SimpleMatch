package com.simplematch.riskservice.submission;

/** Submission command payload paired with the resolved command type selected by the caller. */
public record ResolvedSubmissionCommand(SubmissionCommand payload, CommandType commandType) {
  public ResolvedSubmissionCommand {
    payload = payload == null ? SubmissionCommand.unspecified() : payload;
    commandType = commandType == null ? CommandType.COMMAND_TYPE_UNSPECIFIED : commandType;
  }

  /**
   * Returns a resolved submission command with no payload fields and no resolved command type.
   *
   * @return a completely unspecified resolved submission command
   */
  public static ResolvedSubmissionCommand unspecified() {
    return typedEmpty(CommandType.COMMAND_TYPE_UNSPECIFIED);
  }

  /**
   * Returns a resolved submission command with no payload fields and the provided command type.
   *
   * @param commandType the resolved command type to apply
   * @return a resolved submission command with an empty payload
   */
  public static ResolvedSubmissionCommand typedEmpty(CommandType commandType) {
    return new ResolvedSubmissionCommand(SubmissionCommand.unspecified(), commandType);
  }

  /**
   * Returns a copy with the provided resolved command type applied.
   *
   * @param commandType the resolved command type to apply
   * @return this command when the resolved type is unchanged, otherwise a copy with the new type
   */
  public ResolvedSubmissionCommand withResolvedCommandType(CommandType commandType) {
    final CommandType resolvedType =
        commandType == null ? CommandType.COMMAND_TYPE_UNSPECIFIED : commandType;
    if (resolvedType == this.commandType) {
      return this;
    }
    return new ResolvedSubmissionCommand(payload, resolvedType);
  }

  /**
   * Returns whether the payload is blank and the resolved command type is unspecified.
   *
   * @return {@code true} when the resolved submission command carries no payload and no type
   */
  public boolean isCompletelyUnspecified() {
    return payload.hasNoPayloadFields() && commandType == CommandType.COMMAND_TYPE_UNSPECIFIED;
  }
}

package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubmissionIdempotencyKeyFactoryTest {
  private final SubmissionIdempotencyKeyFactory factory = new SubmissionIdempotencyKeyFactory();

  @Test
  void returnsUnknownKeyForNullCommandWhenCommandTypeIsUnspecified() {
    assertThat(factory.create(null)).isEqualTo("UNKNOWN|");
  }

  @Test
  void usesCommandTypeForTypedEmptyCommand() {
    assertThat(factory.create(ResolvedSubmissionCommand.typedEmpty(CommandType.COMMAND_TYPE_NEW)))
        .isEqualTo("COMMAND_TYPE_NEW|");
  }

  @Test
  void usesNormalizedCommandTypeAndClientOrderId() {
    final ResolvedSubmissionCommand command = ResolvedSubmissionCommand.typedEmpty(CommandType.COMMAND_TYPE_CANCEL)
      .withResolvedCommandType(CommandType.COMMAND_TYPE_NEW);
    final SubmissionCommand normalizedCommand = SubmissionCommand.create(
      new SubmissionCommand.RequestMetadata(
        command.payload().commandId(),
        command.payload().orderId(),
        command.payload().accountId(),
        command.payload().sessionId(),
        "C1",
        command.payload().originalClientOrderId()),
      new SubmissionCommand.OrderDetails(
        command.payload().symbol(),
        command.payload().side(),
        command.payload().quantity(),
        command.payload().price(),
        command.payload().orderType(),
        command.payload().tif()));
    final ResolvedSubmissionCommand normalizedResolvedCommand =
        new ResolvedSubmissionCommand(normalizedCommand, command.commandType());

    assertThat(factory.create(normalizedResolvedCommand))
        .isEqualTo("COMMAND_TYPE_NEW|C1");
  }
}
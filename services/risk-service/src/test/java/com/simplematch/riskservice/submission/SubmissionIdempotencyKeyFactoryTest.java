package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.orders.v1.CommandType;
import org.junit.jupiter.api.Test;

class SubmissionIdempotencyKeyFactoryTest {
  private final SubmissionIdempotencyKeyFactory factory = new SubmissionIdempotencyKeyFactory();

  @Test
  void returnsUnknownKeyForNullCommandWhenCommandTypeIsUnspecified() {
    assertThat(factory.create(null)).isEqualTo("UNKNOWN|");
  }

  @Test
  void usesCommandTypeForEmptyCommand() {
    assertThat(factory.create(SubmissionCommand.empty().withCommandType(CommandType.COMMAND_TYPE_NEW)))
        .isEqualTo("COMMAND_TYPE_NEW|");
  }

  @Test
  void usesNormalizedCommandTypeAndClientOrderId() {
    final SubmissionCommand command = SubmissionCommand.empty()
        .withCommandType(CommandType.COMMAND_TYPE_CANCEL)
        .withCommandType(CommandType.COMMAND_TYPE_NEW);
    final SubmissionCommand normalizedCommand = new SubmissionCommand(
        command.commandId(),
        command.orderId(),
        command.accountId(),
        command.sessionId(),
        "C1",
        command.symbol(),
        command.side(),
        command.quantity(),
        command.price(),
        command.orderType(),
        command.tif(),
        command.commandType(),
        command.originalClientOrderId());

    assertThat(factory.create(normalizedCommand))
        .isEqualTo("COMMAND_TYPE_NEW|C1");
  }
}
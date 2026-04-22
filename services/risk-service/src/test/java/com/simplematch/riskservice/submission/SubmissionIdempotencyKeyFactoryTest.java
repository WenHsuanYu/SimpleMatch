package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import org.junit.jupiter.api.Test;

class SubmissionIdempotencyKeyFactoryTest {
  private final SubmissionIdempotencyKeyFactory factory = new SubmissionIdempotencyKeyFactory();

  @Test
  void returnsUnknownKeyForNullCommandWhenExpectedTypeIsUnspecified() {
    assertThat(factory.create(null, CommandType.COMMAND_TYPE_UNSPECIFIED)).isEqualTo("UNKNOWN|");
  }

  @Test
  void usesExpectedTypeForDefaultCommand() {
    assertThat(factory.create(OrderCommand.getDefaultInstance(), CommandType.COMMAND_TYPE_NEW))
        .isEqualTo("COMMAND_TYPE_NEW|");
  }

  @Test
  void usesExpectedTypeInsteadOfIncomingCommandTypeWhenDifferent() {
    final OrderCommand command = OrderCommand.newBuilder()
        .setClientOrderId("C1")
        .setCommandType(CommandType.COMMAND_TYPE_CANCEL)
        .build();

    assertThat(factory.create(command, CommandType.COMMAND_TYPE_NEW))
        .isEqualTo("COMMAND_TYPE_NEW|C1");
  }
}
package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResolvedSubmissionCommandTest {
  @Test
  void unspecifiedResolvedCommandIsCompletelyUnspecified() {
    final ResolvedSubmissionCommand command = ResolvedSubmissionCommand.unspecified();

    assertThat(command.payload().hasNoPayloadFields()).isTrue();
    assertThat(command.isCompletelyUnspecified()).isTrue();
    assertThat(command.commandType()).isEqualTo(CommandType.COMMAND_TYPE_UNSPECIFIED);
  }

  @Test
  void typedEmptyResolvedCommandRetainsResolvedCommandTypeWithoutPayloadFields() {
    final ResolvedSubmissionCommand command = ResolvedSubmissionCommand.typedEmpty(CommandType.COMMAND_TYPE_CANCEL);

    assertThat(command.payload().hasNoPayloadFields()).isTrue();
    assertThat(command.isCompletelyUnspecified()).isFalse();
    assertThat(command.commandType()).isEqualTo(CommandType.COMMAND_TYPE_CANCEL);
  }
}
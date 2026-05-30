package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubmissionResultTest {
  @Test
  void exposesCommandIdAliasForPersistedRequestId() {
    final SubmissionResult submission = new SubmissionResult(
        "COMMAND_TYPE_NEW|C1",
        "cmd-1",
        "O-C1",
        "C1",
        "",
        CommandType.COMMAND_TYPE_NEW,
        true,
        "",
        "",
        100L);

    assertThat(submission.commandId()).isEqualTo("cmd-1");
  }
}
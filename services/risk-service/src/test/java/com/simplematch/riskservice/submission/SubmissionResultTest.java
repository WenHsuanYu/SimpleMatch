package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SubmissionResultTest {
  @Test
  void exposesCommandIdAliasForPersistedRequestId() {
    final SubmissionResult submission = new SubmissionResult(
        "cmd-1",
        "FIX.4.4:CLIENT->SIMPLEMATCH",
        LocalDate.of(2024, 3, 27),
        "O-C1",
        "C1",
        "",
        CommandType.COMMAND_TYPE_NEW,
        true,
        "",
        "",
        100L);

    assertThat(submission.commandId()).isEqualTo("cmd-1");
    assertThat(submission.sessionId()).isEqualTo("FIX.4.4:CLIENT->SIMPLEMATCH");
    assertThat(submission.tradingDay()).isEqualTo(LocalDate.of(2024, 3, 27));
    assertThat(submission.businessKey())
        .isEqualTo(new SubmissionBusinessKey(
            "FIX.4.4:CLIENT->SIMPLEMATCH",
            LocalDate.of(2024, 3, 27),
            CommandType.COMMAND_TYPE_NEW,
            "C1"));
  }
}
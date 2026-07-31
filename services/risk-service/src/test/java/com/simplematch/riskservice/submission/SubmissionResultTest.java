package com.simplematch.riskservice.submission;

import static com.simplematch.riskservice.testsupport.SubmissionResultFixtures.acceptedNewOrder;
import static com.simplematch.riskservice.testsupport.TestCommandIds.normalize;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SubmissionResultTest {
  @Test
  void exposesCommandIdAliasForPersistedRequestId() {
    final SubmissionResult submission = acceptedNewOrder();

    assertThat(submission.commandId()).isEqualTo(normalize("cmd-1"));
    assertThat(submission.senderCompId()).isEqualTo("CLIENT");
    assertThat(submission.targetCompId()).isEqualTo("SIMPLEMATCH");
    assertThat(submission.tradingDay()).isEqualTo(LocalDate.of(2024, 3, 27));
    assertThat(submission.businessKey())
        .isEqualTo(
            new SubmissionBusinessKey(
                "CLIENT",
                "SIMPLEMATCH",
                LocalDate.of(2024, 3, 27),
                CommandType.COMMAND_TYPE_NEW,
                "C1",
                false));
  }
}

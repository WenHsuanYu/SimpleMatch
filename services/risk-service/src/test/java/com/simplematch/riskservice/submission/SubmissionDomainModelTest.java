package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubmissionDomainModelTest {
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 7, 28);

  @DisplayName("Rejected outcomes retain one stable rejection value")
  @Test
  void composesRejectedSubmission() {
    final SubmissionResult result =
        new SubmissionResult(
            new SubmissionReference(
                new SubmissionCommand.CommandId("request-1"),
                new SubmissionCommand.OrderId("order-1"),
                CommandType.COMMAND_TYPE_NEW),
            new FixSubmissionIdentity(
                new SubmissionCommand.SenderCompId("CLIENT"),
                new SubmissionCommand.TargetCompId("SIMPLEMATCH"),
                TRADING_DAY,
                new SubmissionCommand.ClOrdId("C1"),
                SubmissionCommand.OrigClOrdId.empty()),
            new PersistedFixIdentity(
                new SubmissionCommand.ClOrdId("C1"), SubmissionCommand.OrigClOrdId.empty(), false),
            SubmissionOutcome.rejectedOutcome(
                new SubmissionRejection(
                    new SubmissionRejection.Code("MISSING_PRICE"),
                    new SubmissionRejection.Detail("price is required"))),
            1L);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("MISSING_PRICE");
    assertThat(result.businessKey().clOrdId()).isEqualTo("C1");
  }

  @DisplayName("Accepted outcomes cannot carry a rejection")
  @Test
  void acceptedOutcomeRejectsFailureData() {
    final SubmissionRejection rejection =
        new SubmissionRejection(
            new SubmissionRejection.Code("INVALID"),
            new SubmissionRejection.Detail("invalid command"));

    assertThatThrownBy(() -> new SubmissionOutcome(true, rejection))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("accepted submission must not carry a rejection");
  }
}

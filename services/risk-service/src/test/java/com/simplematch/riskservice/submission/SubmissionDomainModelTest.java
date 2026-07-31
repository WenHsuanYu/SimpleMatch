package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.riskservice.testsupport.SubmissionResultFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubmissionDomainModelTest {
  @DisplayName("Rejected outcomes retain one stable rejection value")
  @Test
  void composesRejectedSubmission() {
    final SubmissionResult result = SubmissionResultFixtures.rejectedMissingPrice();

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

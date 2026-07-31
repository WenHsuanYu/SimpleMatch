package com.simplematch.riskservice.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.riskservice.submission.SubmissionCommand;
import com.simplematch.riskservice.submission.SubmissionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubmissionResultFixturesTest {
  @DisplayName("accepted scenario carries no rejection data")
  @Test
  void acceptedScenarioCarriesNoRejectionData() {
    final SubmissionResult result = SubmissionResultFixtures.acceptedNewOrder();

    assertThat(result.accepted()).isTrue();
    assertThat(result.outcome().rejection()).isNull();
    assertThat(result.reasonCode()).isEmpty();
    assertThat(result.reasonText()).isEmpty();
  }

  @DisplayName("rejected scenario carries a stable nonblank reason")
  @Test
  void rejectedScenarioCarriesStableReason() {
    final SubmissionResult result = SubmissionResultFixtures.rejectedMissingPrice();

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome().rejection()).isNotNull();
    assertThat(result.reasonCode()).isEqualTo("MISSING_PRICE");
    assertThat(result.reasonText()).isEqualTo("price is required for limit orders");
  }

  @DisplayName("surrogated scenario keeps persisted identity and flag together")
  @Test
  void surrogatedScenarioKeepsPersistedIdentityAndFlagTogether() {
    final SubmissionResult result = SubmissionResultFixtures.rejectedOversizedClientOrderId();

    assertThat(result.clOrdId()).hasSize(300);
    assertThat(result.origClOrdId()).hasSize(300);
    assertThat(result.persistedFixIdentity().clOrdId())
        .isEqualTo(new SubmissionCommand.ClOrdId("a".repeat(64)));
    assertThat(result.persistedFixIdentity().origClOrdId())
        .isEqualTo(new SubmissionCommand.OrigClOrdId("b".repeat(64)));
    assertThat(result.businessKeySurrogated()).isTrue();
  }

  @Test
  void namedCancelScenarioContainsRawAndPersistedOrderIdentities() {
    final SubmissionResult result = SubmissionResultFixtures.acceptedCancelOrder();

    assertThat(result.clOrdId()).isEqualTo("CXL-1");
    assertThat(result.origClOrdId()).isEqualTo("C1");
    assertThat(result.persistedClOrdId()).isEqualTo("CXL-1");
    assertThat(result.persistedOrigClOrdId()).isEqualTo("C1");
    assertThat(result.businessKeySurrogated()).isFalse();
  }
}

package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the machine code and human detail contracts at the public domain boundary. */
class AdmissionFailureTest {
  @DisplayName("reason codes preserve any uppercase ASCII token, including future codes")
  @ParameterizedTest
  @ValueSource(strings = {"A", "INVALID_COMMAND", "LIMIT_2_EXCEEDED", "NEW_REASON_2027"})
  void acceptsMachineReadableReasonCode(String code) {
    assertThat(new AdmissionFailure.ReasonCode(code).value()).isEqualTo(code);
  }

  @DisplayName("reason codes reject null and values outside the ASCII token grammar")
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
      " ", "\t\n", "\u3000", "invalid", "INVALID-COMMAND", "INVALID COMMAND",
      " INVALID", "INVALID ", "INVALID\n", "1INVALID", "_INVALID", "無效", "ÉCHEC"
  })
  void rejectsInvalidMachineReadableReasonCode(String code) {
    assertThatThrownBy(() -> new AdmissionFailure.ReasonCode(code))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("application details reject null, empty, and Java blank values")
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n", "\u001c", "\u2003", "\u3000"})
  void rejectsBlankDetail(String detail) {
    assertThatThrownBy(() -> new AdmissionFailure.Detail(detail))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("failure factories preserve the original nonblank human detail")
  @Test
  void factoryPreservesDetailWithoutTrimming() {
    final String detail = " \tInvalid command\u3000";

    final AdmissionFailure failure = AdmissionFailure.invalidCommand(detail);

    assertThat(failure.reasonCode().value()).isEqualTo("INVALID_COMMAND");
    assertThat(failure.detail().value()).isEqualTo(detail);
  }
}

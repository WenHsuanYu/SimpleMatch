package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixExecutionIdentityTest {
  @DisplayName("execution identity rejects a missing execution id")
  @Test
  void rejectsMissingExecutionId() {
    assertThatThrownBy(
            () -> new FixExecutionIdentity(null, Instant.parse("2024-03-27T08:09:10.123Z")))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("executionId");
  }

  @DisplayName("execution identity rejects a missing transaction time")
  @Test
  void rejectsMissingTransactTime() {
    assertThatThrownBy(
            () ->
                new FixExecutionIdentity(
                    new FixExecutionIdentity.ExecutionId("E1"), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("transactTime");
  }
}

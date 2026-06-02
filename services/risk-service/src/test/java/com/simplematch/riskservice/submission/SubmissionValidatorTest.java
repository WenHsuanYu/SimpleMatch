package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.resolvedCancelOrder;
import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.resolvedNewOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SubmissionValidatorTest {
  private static final long NOW = 1_700_000_000_123L;

  private final SubmissionValidator validator = new SubmissionValidator(
      Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

  @Test
  void returnsAcceptedDecisionForValidNewOrder() {
    final SubmissionDecision decision = validator.evaluate(resolvedNewOrder("cmd-1", "O-C1", "C1"));

    assertThat(decision.submission().accepted()).isTrue();
    assertThat(decision.submission().commandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
    assertThat(decision.submission().createdAtUnixMs()).isEqualTo(NOW);
    assertThat(decision.submission().tradingDay())
      .isEqualTo(LocalDate.ofInstant(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    assertThat(decision.command().commandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
  }

  @Test
  void rejectsMissingPriceForLimitOrder() {
    final ResolvedSubmissionCommand command = resolvedNewOrder(
        "cmd-1",
        "O-C1",
        "C1",
        "",
        OrderType.ORDER_TYPE_LIMIT);
    final SubmissionDecision decision = validator.evaluate(command);

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("MISSING_PRICE");
  }

  @Test
  void acceptsMarketOrderWithoutPrice() {
    final ResolvedSubmissionCommand command = resolvedNewOrder(
        "cmd-1",
        "O-C1",
        "C1",
        "",
        OrderType.ORDER_TYPE_MARKET);
    final SubmissionDecision decision = validator.evaluate(command);

    assertThat(decision.submission().accepted()).isTrue();
    assertThat(decision.submission().reasonCode()).isEmpty();
    assertThat(decision.submission().reasonText()).isEmpty();
  }

  @Test
  void rejectsCancelWithoutOriginalClientOrderId() {
    final SubmissionDecision decision = validator.evaluate(
        resolvedCancelOrder("cmd-1", "O-C1", "CXL-1", ""));

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("MISSING_ORIG_CL_ORD_ID");
  }

  @Test
  void returnsUnspecifiedCommandWhenCommandAndExpectedTypeAreUnspecified() {
    final SubmissionDecision decision = validator.evaluate(null);

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("EMPTY_COMMAND");
    assertThat(decision.submission().commandType()).isEqualTo(CommandType.COMMAND_TYPE_UNSPECIFIED);
    assertThat(decision.command()).isEqualTo(ResolvedSubmissionCommand.unspecified());
  }

  @Test
  void rejectsTypedEmptyNewOrderAfterNormalization() {
    final SubmissionDecision decision = validator.evaluate(
      ResolvedSubmissionCommand.typedEmpty(CommandType.COMMAND_TYPE_NEW));

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("MISSING_CL_ORD_ID");
    assertThat(decision.command().commandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
  }
}
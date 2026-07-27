package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.resolvedCancelOrder;
import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.resolvedNewOrder;
import static com.simplematch.riskservice.testsupport.TestCommandIds.normalize;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
  void rejectsNonUuidRequestId() {
    final SubmissionDecision decision = validator.evaluate(resolvedNewOrder("not-a-uuid", "O-C1", "C1"));

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("INVALID_REQUEST_ID");
    assertThat(decision.submission().reasonText()).isEqualTo("request_id must be a UUID");
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("missingSessionIdentityCases")
  void rejectsMissingSessionIdentity(
      String ignoredCaseName,
      ResolvedSubmissionCommand command,
      String expectedReasonCode) {
    final SubmissionDecision decision = validator.evaluate(command);

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo(expectedReasonCode);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("oversizedIdentifierCases")
  void rejectsOversizedIdentifiersBeforePersistence(
      String ignoredCaseName,
      ResolvedSubmissionCommand command,
      String expectedReasonCode) {
    final SubmissionDecision decision = validator.evaluate(command);

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo(expectedReasonCode);
  }

  @Test
  void rejectsOversizedClOrdIdWhileKeepingRawValueForResponse() {
    final String oversized = "X".repeat(300);

    final SubmissionDecision decision = validator.evaluate(resolvedNewOrder("cmd-1", "O-C1", oversized));

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("OVERSIZED_CL_ORD_ID");
    assertThat(decision.submission().clOrdId()).isEqualTo(oversized);
    assertThat(decision.submission().persistedClOrdId())
        .hasSize(64)
        .isNotEqualTo(oversized);
    assertThat(decision.submission().businessKeySurrogated()).isTrue();
    assertThat(decision.submission().businessKey().clOrdId())
        .isEqualTo(decision.submission().persistedClOrdId());
    assertThat(decision.submission().businessKey().businessKeySurrogated()).isTrue();
  }

  @Test
  void rejectsOversizedOrigClOrdIdWhileKeepingRawValueForResponse() {
    final String oversized = "X".repeat(300);

    final SubmissionDecision decision = validator.evaluate(
        resolvedCancelOrder("cmd-1", "O-C1", "CXL-1", oversized));

    assertThat(decision.submission().accepted()).isFalse();
    assertThat(decision.submission().reasonCode()).isEqualTo("OVERSIZED_ORIG_CL_ORD_ID");
    assertThat(decision.submission().origClOrdId()).isEqualTo(oversized);
    assertThat(decision.submission().persistedOrigClOrdId())
      .hasSizeLessThanOrEqualTo(64)
        .isNotEqualTo(oversized);
  }

  private static Stream<Arguments> oversizedIdentifierCases() {
    final String oversized = "X".repeat(300);
    return Stream.of(
        Arguments.of(
            "oversized request_id",
            resolvedNewOrder(oversized, "O-C1", "C1"),
            "OVERSIZED_REQUEST_ID"),
        Arguments.of(
            "oversized order_id",
            resolvedNewOrder("cmd-1", oversized, "C1"),
        "OVERSIZED_ORDER_ID"),
      Arguments.of(
        "oversized sender_comp_id",
        resolvedNewOrderWithSession("cmd-1", "O-C1", "C1", oversized, "SIMPLEMATCH"),
        "OVERSIZED_SENDER_COMP_ID"),
      Arguments.of(
        "oversized target_comp_id",
        resolvedNewOrderWithSession("cmd-1", "O-C1", "C1", "CLIENT", oversized),
        "OVERSIZED_TARGET_COMP_ID"),
      Arguments.of(
        "oversized symbol",
        resolvedNewOrderWithSymbol("cmd-1", "O-C1", "C1", oversized),
        "OVERSIZED_SYMBOL"));
    }

    private static Stream<Arguments> missingSessionIdentityCases() {
    return Stream.of(
      Arguments.of(
        "missing sender_comp_id",
        resolvedNewOrderWithSession("cmd-1", "O-C1", "C1", "", "SIMPLEMATCH"),
        "MISSING_SENDER_COMP_ID"),
      Arguments.of(
        "missing target_comp_id",
        resolvedNewOrderWithSession("cmd-1", "O-C1", "C1", "CLIENT", ""),
        "MISSING_TARGET_COMP_ID"));
  }

    private static ResolvedSubmissionCommand resolvedNewOrderWithSession(
      String commandId,
      String orderId,
      String clOrdId,
      String senderCompId,
      String targetCompId) {
    return new ResolvedSubmissionCommand(
      SubmissionCommand.create(
        new SubmissionCommand.RequestMetadata(
          normalize(commandId),
          orderId,
          "ACC-1",
          senderCompId,
          targetCompId,
          clOrdId,
          ""),
        new SubmissionCommand.OrderDetails(
          "AAPL",
          Side.SIDE_BUY,
          "10",
          "101.25",
          OrderType.ORDER_TYPE_LIMIT,
          TimeInForce.TIME_IN_FORCE_ROD)),
      CommandType.COMMAND_TYPE_NEW);
    }

  private static ResolvedSubmissionCommand resolvedNewOrderWithSymbol(
      String commandId,
      String orderId,
      String clOrdId,
      String symbol) {
    return new ResolvedSubmissionCommand(
        SubmissionCommand.create(
            new SubmissionCommand.RequestMetadata(
            normalize(commandId),
                orderId,
                "ACC-1",
                "CLIENT",
                "SIMPLEMATCH",
                clOrdId,
                ""),
            new SubmissionCommand.OrderDetails(
                symbol,
                Side.SIDE_BUY,
                "10",
                "101.25",
                OrderType.ORDER_TYPE_LIMIT,
                TimeInForce.TIME_IN_FORCE_ROD)),
        CommandType.COMMAND_TYPE_NEW);
  }
}
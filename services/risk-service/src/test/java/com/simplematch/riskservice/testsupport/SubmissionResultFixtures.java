package com.simplematch.riskservice.testsupport;

import com.simplematch.riskservice.submission.CommandType;
import com.simplematch.riskservice.submission.FixSubmissionIdentity;
import com.simplematch.riskservice.submission.PersistedFixIdentity;
import com.simplematch.riskservice.submission.SubmissionCommand;
import com.simplematch.riskservice.submission.SubmissionOutcome;
import com.simplematch.riskservice.submission.SubmissionReference;
import com.simplematch.riskservice.submission.SubmissionRejection;
import com.simplematch.riskservice.submission.SubmissionResult;
import java.time.LocalDate;

/** Creates complete named {@link SubmissionResult} scenarios for risk-service tests. */
public final class SubmissionResultFixtures {
  private static final LocalDate TRADING_DAY = LocalDate.of(2024, 3, 27);
  private static final String SENDER_COMP_ID = "CLIENT";
  private static final String TARGET_COMP_ID = "SIMPLEMATCH";

  private SubmissionResultFixtures() {}

  /** Creates an accepted new-order result with a plain persisted FIX identity. */
  public static SubmissionResult acceptedNewOrder() {
    return result(
        reference("cmd-1", "O-C1", CommandType.COMMAND_TYPE_NEW),
        fixIdentity("C1", ""),
        persistedFixIdentity("C1", "", false),
        SubmissionOutcome.acceptedOutcome(),
        100L);
  }

  /** Creates an accepted cancel result with both raw FIX order identifiers. */
  public static SubmissionResult acceptedCancelOrder() {
    return result(
        reference("cmd-2", "O-C1", CommandType.COMMAND_TYPE_CANCEL),
        fixIdentity("CXL-1", "C1"),
        persistedFixIdentity("CXL-1", "C1", false),
        SubmissionOutcome.acceptedOutcome(),
        101L);
  }

  /** Creates a rejected new-order result with a stable missing-price reason. */
  public static SubmissionResult rejectedMissingPrice() {
    return result(
        reference("cmd-1", "O-C1", CommandType.COMMAND_TYPE_NEW),
        fixIdentity("C1", ""),
        persistedFixIdentity("C1", "", false),
        rejectedOutcome("MISSING_PRICE", "price is required for limit orders"),
        100L);
  }

  /** Creates a rejected result for an empty command with the durable fallback identity. */
  public static SubmissionResult rejectedEmptyCommand() {
    return result(
        reference("", "", CommandType.COMMAND_TYPE_UNSPECIFIED),
        fixIdentity("", ""),
        persistedFixIdentity("", "", false),
        rejectedOutcome("EMPTY_COMMAND", "risk command payload is required"),
        102L);
  }

  /** Creates a rejected result whose durable FIX identity uses a deterministic surrogate. */
  public static SubmissionResult rejectedOversizedClientOrderId() {
    return result(
        reference("cmd-3", "O-C3", CommandType.COMMAND_TYPE_CANCEL),
        fixIdentity("X".repeat(300), "Y".repeat(300)),
        persistedFixIdentity("a".repeat(64), "b".repeat(64), true),
        rejectedOutcome("OVERSIZED_CL_ORD_ID", "cl_ord_id must be <= 64 characters"),
        102L);
  }

  /** Creates an accepted result for a second session sharing a client order identity. */
  public static SubmissionResult acceptedSecondSession() {
    return result(
        reference("cmd-2", "O-C2", CommandType.COMMAND_TYPE_NEW),
        fixIdentity("CXL-1", "C1"),
        persistedFixIdentity("CXL-1", "C1", false),
        SubmissionOutcome.acceptedOutcome(),
        101L);
  }

  /** Creates an accepted result with a full-length, non-surrogated persisted client order key. */
  public static SubmissionResult acceptedPlainPersistedBusinessKey() {
    return result(
        reference("cmd-plain", "O-C-plain", CommandType.COMMAND_TYPE_NEW),
        fixIdentity("a".repeat(64), ""),
        persistedFixIdentity("a".repeat(64), "", false),
        SubmissionOutcome.acceptedOutcome(),
        103L);
  }

  /** Creates a rejected result with the same persisted key and a true surrogate flag. */
  public static SubmissionResult rejectedSurrogatedBusinessKey() {
    return result(
        reference("cmd-surrogate", "O-C-surrogate", CommandType.COMMAND_TYPE_NEW),
        fixIdentity("X".repeat(300), ""),
        persistedFixIdentity("a".repeat(64), "", true),
        rejectedOutcome("OVERSIZED_CL_ORD_ID", "cl_ord_id must be <= 64 characters"),
        104L);
  }

  private static SubmissionResult result(
      SubmissionReference reference,
      FixSubmissionIdentity fixIdentity,
      PersistedFixIdentity persistedFixIdentity,
      SubmissionOutcome outcome,
      long createdAtUnixMs) {
    return new SubmissionResult(
        reference, fixIdentity, persistedFixIdentity, outcome, createdAtUnixMs);
  }

  private static SubmissionReference reference(
      String commandId, String orderId, CommandType commandType) {
    return new SubmissionReference(
        new SubmissionCommand.CommandId(TestCommandIds.normalize(commandId)),
        new SubmissionCommand.OrderId(orderId),
        commandType);
  }

  private static FixSubmissionIdentity fixIdentity(String clOrdId, String origClOrdId) {
    return new FixSubmissionIdentity(
        new SubmissionCommand.SenderCompId(SENDER_COMP_ID),
        new SubmissionCommand.TargetCompId(TARGET_COMP_ID),
        TRADING_DAY,
        new SubmissionCommand.ClOrdId(clOrdId),
        new SubmissionCommand.OrigClOrdId(origClOrdId));
  }

  private static PersistedFixIdentity persistedFixIdentity(
      String clOrdId, String origClOrdId, boolean surrogated) {
    return new PersistedFixIdentity(
        new SubmissionCommand.ClOrdId(clOrdId),
        new SubmissionCommand.OrigClOrdId(origClOrdId),
        surrogated);
  }

  private static SubmissionOutcome rejectedOutcome(String code, String detail) {
    return SubmissionOutcome.rejectedOutcome(
        new SubmissionRejection(
            new SubmissionRejection.Code(code), new SubmissionRejection.Detail(detail)));
  }
}

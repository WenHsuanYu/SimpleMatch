package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionFailure;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.wal.WalRecord;
import com.simplematch.quickfixgateway.wal.WalRecoveryJournal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.SessionID;

/** Submits commands to Risk and renders only outcomes that the gateway can prove. */
final class RiskSubmissionResponder {
  private static final Logger logger = LoggerFactory.getLogger(RiskSubmissionResponder.class);
  private static final String UNKNOWN_OUTCOME_CLIENT_TEXT =
      "SYSTEM_ERROR: order outcome is pending confirmation; no client action is required";

  private final RiskSubmissionClient riskSubmissionClient;
  private final FixSessionMessageSender fixSessionMessageSender;
  private final FixMessageMapper fixMessageMapper;
  private final RiskRecoveryStateRecorder recoveryStateRecorder;

  RiskSubmissionResponder(
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper,
      WalRecoveryJournal recoveryJournal) {
    this.riskSubmissionClient = riskSubmissionClient;
    this.fixSessionMessageSender = fixSessionMessageSender;
    this.fixMessageMapper = fixMessageMapper;
    this.recoveryStateRecorder = new RiskRecoveryStateRecorder(recoveryJournal);
  }

  RiskSubmissionResult submitNewOrder(
      OrderCommand command, SessionID sessionId, WalRecord walRecord, Instant now) {
    try {
      final RiskSubmissionResult submission = riskSubmissionClient.submitNewOrder(command);
      recoveryStateRecorder.record(command, walRecord, submission);
      if (submission.rejected()) {
        fixSessionMessageSender.send(
            sessionId,
            fixMessageMapper.buildRejected(
                FixOrderSnapshot.from(walRecord),
                new FixExecutionIdentity(
                    new FixExecutionIdentity.ExecutionId("RJ-" + walRecord.recordId()), now),
                businessOutcomeText(submission)));
      } else if (submission.unknown()) {
        logUnknownOutcome("submit", command, walRecord, submission, null);
        sendUnknownNewOrder(sessionId, walRecord, now);
      }
      return submission;
    } catch (RuntimeException error) {
      return unknownNewOrder(command, sessionId, walRecord, now, error);
    }
  }

  RiskSubmissionResult submitCancelOrder(
      OrderCommand command, SessionID sessionId, WalRecord walRecord, char ordStatus) {
    try {
      final RiskSubmissionResult submission = riskSubmissionClient.submitCancel(command);
      recoveryStateRecorder.record(command, walRecord, submission);
      if (submission.rejected()) {
        sendCancelRejection(sessionId, walRecord, ordStatus, businessOutcomeText(submission));
      } else if (submission.unknown()) {
        logUnknownOutcome("cancel", command, walRecord, submission, null);
      }
      return submission;
    } catch (RuntimeException error) {
      return unknownCancelOrder(command, walRecord, error);
    }
  }

  void rejectInbound(
      FixInboundValidationFailure failure,
      SessionID sessionId,
      String orderId,
      String cancelClOrdId,
      String origClOrdId) {
    fixSessionMessageSender.send(
        sessionId,
        fixMessageMapper.buildOrderCancelReject(
            orderId,
            cancelClOrdId,
            origClOrdId,
            '8',
            failure.reasonCode() + ": " + failure.reasonText()));
  }

  private RiskSubmissionResult unknownNewOrder(
      OrderCommand command,
      SessionID sessionId,
      WalRecord walRecord,
      Instant now,
      RuntimeException error) {
    final RiskSubmissionFailure failure =
        failure(error, "submit", "risk-service submit failed");
    final RiskSubmissionResult unknown =
        RiskSubmissionResult.unknown(
            walRecord.orderId(), failure.reasonCode(), failure.reasonText());
    recoveryStateRecorder.record(command, walRecord, unknown);
    logUnknownOutcome("submit", command, walRecord, unknown, error);
    sendUnknownNewOrder(sessionId, walRecord, now);
    return unknown;
  }

  private RiskSubmissionResult unknownCancelOrder(
      OrderCommand command, WalRecord walRecord, RuntimeException error) {
    final RiskSubmissionFailure failure =
        failure(error, "cancel", "risk-service cancel failed");
    final RiskSubmissionResult unknown =
        RiskSubmissionResult.unknown(
            walRecord.orderId(), failure.reasonCode(), failure.reasonText());
    recoveryStateRecorder.record(command, walRecord, unknown);
    logUnknownOutcome("cancel", command, walRecord, unknown, error);
    // Do not emit OrderCancelReject: a missing RPC response does not prove
    // that Risk rejected the cancel.
    return unknown;
  }

  private void sendUnknownNewOrder(SessionID sessionId, WalRecord walRecord, Instant now) {
    fixSessionMessageSender.send(
        sessionId,
        fixMessageMapper.buildPendingNew(
            FixOrderSnapshot.from(walRecord),
            new FixExecutionIdentity(
                new FixExecutionIdentity.ExecutionId("UN-" + walRecord.recordId()), now),
            UNKNOWN_OUTCOME_CLIENT_TEXT));
  }

  private void sendCancelRejection(
      SessionID sessionId, WalRecord walRecord, char ordStatus, String text) {
    fixSessionMessageSender.send(
        sessionId,
        fixMessageMapper.buildOrderCancelReject(
            walRecord.orderId(),
            walRecord.clOrdId(),
            walRecord.origClOrdId(),
            ordStatus,
            text));
  }

  private void logUnknownOutcome(
      String operation,
      OrderCommand command,
      WalRecord walRecord,
      RiskSubmissionResult submission,
      RuntimeException error) {
    if (error == null) {
      logger.warn(
          "risk-service {} outcome unknown command_id={} order_id={} reason_code={} reason_text={}",
          operation,
          command.getCommandId(),
          walRecord.orderId(),
          submission.reasonCode(),
          submission.reasonText());
      return;
    }
    logger.warn(
        "risk-service {} outcome unknown command_id={} order_id={} reason_code={} reason_text={}",
        operation,
        command.getCommandId(),
        walRecord.orderId(),
        submission.reasonCode(),
        submission.reasonText(),
        error);
  }

  private RiskSubmissionFailure failure(
      RuntimeException error, String operation, String fallbackReasonText) {
    if (error instanceof RiskSubmissionFailure failure) {
      return failure;
    }
    return RiskSubmissionFailure.unavailable(
        operation, 1, new IllegalStateException(fallbackReasonText, error));
  }

  private String businessOutcomeText(RiskSubmissionResult submission) {
    final String reasonCode = submission.reasonCode();
    final String reasonText = submission.reasonText();
    if (reasonCode == null || reasonCode.isBlank()) {
      return reasonText;
    }
    if (reasonText == null || reasonText.isBlank()) {
      return reasonCode;
    }
    return reasonCode + ": " + reasonText;
  }
}

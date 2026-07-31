package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionFailure;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.SessionID;

/** Submits risk decisions and renders their protocol-level rejection responses. */
final class RiskSubmissionResponder {
  private static final Logger logger = LoggerFactory.getLogger(RiskSubmissionResponder.class);

  private final RiskSubmissionClient riskSubmissionClient;
  private final FixSessionMessageSender fixSessionMessageSender;
  private final FixMessageMapper fixMessageMapper;

  RiskSubmissionResponder(
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper) {
    this.riskSubmissionClient = riskSubmissionClient;
    this.fixSessionMessageSender = fixSessionMessageSender;
    this.fixMessageMapper = fixMessageMapper;
  }

  RiskSubmissionResult submitNewOrder(
      OrderCommand command, SessionID sessionId, WalRecord walRecord, Instant now) {
    try {
      final RiskSubmissionResult submission = riskSubmissionClient.submitNewOrder(command);
      if (!submission.accepted()) {
        fixSessionMessageSender.send(
            sessionId,
            fixMessageMapper.buildRejected(
                FixOrderSnapshot.from(walRecord),
                new FixExecutionIdentity(
                    new FixExecutionIdentity.ExecutionId("RJ-" + walRecord.recordId()), now),
                rejectText(submission)));
      }
      return submission;
    } catch (RuntimeException error) {
      return unavailableNewOrder(command, sessionId, walRecord, now, error);
    }
  }

  RiskSubmissionResult submitCancelOrder(
      OrderCommand command, SessionID sessionId, WalRecord walRecord, char ordStatus) {
    try {
      final RiskSubmissionResult submission = riskSubmissionClient.submitCancel(command);
      if (!submission.accepted()) {
        sendCancelRejection(sessionId, walRecord, ordStatus, rejectText(submission));
      }
      return submission;
    } catch (RuntimeException error) {
      final RiskSubmissionFailure failure = failure(error, "risk-service cancel failed");
      logger.warn(
          "risk-service cancel failed for command_id={} reason_code={}",
          command.getCommandId(),
          failure.reasonCode(),
          error);
      sendCancelRejection(
          sessionId, walRecord, ordStatus, failure.reasonCode() + ": " + failure.reasonText());
      return new RiskSubmissionResult(
          walRecord.orderId(), false, failure.reasonCode(), failure.reasonText());
    }
  }

  void rejectIdentity(
      FixIdentityValidationFailure failure,
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

  private RiskSubmissionResult unavailableNewOrder(
      OrderCommand command,
      SessionID sessionId,
      WalRecord walRecord,
      Instant now,
      RuntimeException error) {
    final RiskSubmissionFailure failure = failure(error, "risk-service submit failed");
    logger.warn(
        "risk-service submit failed for command_id={} reason_code={}",
        command.getCommandId(),
        failure.reasonCode(),
        error);
    fixSessionMessageSender.send(
        sessionId,
        fixMessageMapper.buildRejected(
            FixOrderSnapshot.from(walRecord),
            new FixExecutionIdentity(
                new FixExecutionIdentity.ExecutionId("RJ-" + walRecord.recordId()), now),
            failure.reasonCode() + ": " + failure.reasonText()));
    return new RiskSubmissionResult(
        walRecord.orderId(), false, failure.reasonCode(), failure.reasonText());
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

  private RiskSubmissionFailure failure(RuntimeException error, String fallbackReasonText) {
    if (error instanceof RiskSubmissionFailure failure) {
      return failure;
    }
    return RiskSubmissionFailure.unavailable(
        "submit", 1, new IllegalStateException(fallbackReasonText, error));
  }

  private String rejectText(RiskSubmissionResult submission) {
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

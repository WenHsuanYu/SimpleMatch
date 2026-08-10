package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.wal.WalDurableCommandWriter;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.time.Clock;
import java.time.Instant;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.ClOrdID;
import quickfix.field.OrigClOrdID;

/** Owns the durable admission path for a FIX OrderCancelRequest message. */
final class CancelOrderFixMessageHandler {
  private final WalDurableCommandWriter durableCommandWriter;
  private final OrderSessionRegistry orderSessionRegistry;
  private final RiskSubmissionResponder riskSubmissionResponder;
  private final CommandIdGenerator commandIdGenerator;
  private final Clock clock;
  private final GatewayAdmissionGate admissionGate;

  CancelOrderFixMessageHandler(
      WalDurableCommandWriter durableCommandWriter,
      OrderSessionRegistry orderSessionRegistry,
      RiskSubmissionResponder riskSubmissionResponder,
      CommandIdGenerator commandIdGenerator,
      Clock clock,
      GatewayAdmissionGate admissionGate) {
    this.durableCommandWriter = durableCommandWriter;
    this.orderSessionRegistry = orderSessionRegistry;
    this.riskSubmissionResponder = riskSubmissionResponder;
    this.commandIdGenerator = commandIdGenerator;
    this.clock = clock;
    this.admissionGate = admissionGate;
  }

  void handle(Message message, SessionID sessionId) throws FieldNotFound {
    final String origClOrdId = FixInboundFieldValues.optionalString(message, OrigClOrdID.FIELD);
    final String cancelClOrdId = FixInboundFieldValues.optionalString(message, ClOrdID.FIELD);
    if (!admissionGate.allowsAdmission()) {
      riskSubmissionResponder.rejectInbound(
          admissionGate.cancelFailure(),
          sessionId,
          rejectionOrderIdFor(origClOrdId),
          cancelClOrdId,
          origClOrdId);
      return;
    }
    final FixInboundIdentity identity =
        FixInboundIdentityValidator.validateCancel(sessionId, cancelClOrdId, origClOrdId);
    if (!identity.valid()) {
      riskSubmissionResponder.rejectInbound(
          identity.failure(),
          sessionId,
          rejectionOrderIdFor(origClOrdId),
          cancelClOrdId,
          origClOrdId);
      return;
    }
    final String orderId = FixInboundCommandFactory.orderIdFor(origClOrdId);
    final OrderSessionState existing = orderSessionRegistry.find(orderId).orElse(null);
    final WalRecord walRecord;
    try {
      walRecord =
          FixInboundCommandFactory.cancelOrder(
              message,
              identity,
              existing,
              commandIdGenerator.nextCommandId(),
              Instant.now(clock));
    } catch (FieldNotFound | IllegalArgumentException failure) {
      riskSubmissionResponder.rejectInbound(
          FixInboundValidationFailure.fromException("INVALID_CANCEL", failure),
          sessionId,
          orderId,
          cancelClOrdId,
          origClOrdId);
      return;
    }
    durableCommandWriter.appendForSubmission(walRecord);
    if (!riskSubmissionResponder
        .submitCancelOrder(
            sessionId,
            walRecord,
            existing == null ? '8' : existing.lifecycle().currentOrdStatus())
        .accepted()) {
      return;
    }
    orderSessionRegistry.registerCancelRequest(sessionId, walRecord);
  }

  private String rejectionOrderIdFor(String origClOrdId) {
    return origClOrdId.isBlank() ? "" : FixInboundCommandFactory.orderIdFor(origClOrdId);
  }
}

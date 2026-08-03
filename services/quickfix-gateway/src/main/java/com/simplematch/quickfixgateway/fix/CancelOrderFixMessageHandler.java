package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.wal.WalAppender;
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
  private final WalAppender walAppender;
  private final OrderSessionRegistry orderSessionRegistry;
  private final RiskSubmissionResponder riskSubmissionResponder;
  private final FixCompatibilityCommandPublisher compatibilityPublisher;
  private final CommandIdGenerator commandIdGenerator;
  private final Clock clock;

  CancelOrderFixMessageHandler(
      WalAppender walAppender,
      OrderSessionRegistry orderSessionRegistry,
      RiskSubmissionResponder riskSubmissionResponder,
      FixCompatibilityCommandPublisher compatibilityPublisher,
      CommandIdGenerator commandIdGenerator,
      Clock clock) {
    this.walAppender = walAppender;
    this.orderSessionRegistry = orderSessionRegistry;
    this.riskSubmissionResponder = riskSubmissionResponder;
    this.compatibilityPublisher = compatibilityPublisher;
    this.commandIdGenerator = commandIdGenerator;
    this.clock = clock;
  }

  void handle(Message message, SessionID sessionId) throws FieldNotFound {
    final String origClOrdId = FixInboundFieldValues.optionalString(message, OrigClOrdID.FIELD);
    final String cancelClOrdId = FixInboundFieldValues.optionalString(message, ClOrdID.FIELD);
    final FixInboundIdentity identity =
        FixInboundIdentityValidator.validateCancel(sessionId, cancelClOrdId, origClOrdId);
    if (!identity.valid()) {
      riskSubmissionResponder.rejectInbound(
          identity.failure(),
          sessionId,
          FixInboundCommandFactory.orderIdFor(origClOrdId),
          cancelClOrdId,
          origClOrdId);
      return;
    }
    final OrderSessionState existing =
        orderSessionRegistry.find(FixInboundCommandFactory.orderIdFor(origClOrdId)).orElse(null);
    final WalRecord walRecord;
    try {
      walRecord =
          FixInboundCommandFactory.cancelOrder(
              message, identity, existing, commandIdGenerator.nextCommandId(), Instant.now(clock));
    } catch (FieldNotFound | IllegalArgumentException failure) {
      riskSubmissionResponder.rejectInbound(
          FixInboundValidationFailure.fromException("INVALID_CANCEL", failure),
          sessionId,
          FixInboundCommandFactory.orderIdFor(origClOrdId),
          cancelClOrdId,
          origClOrdId);
      return;
    }
    walAppender.appendAndFlush(walRecord);
    final OrderCommand command = walRecord.toOrderCommand();
    if (!riskSubmissionResponder
        .submitCancelOrder(
            command,
            sessionId,
            walRecord,
            existing == null ? '8' : existing.lifecycle().currentOrdStatus())
        .accepted()) {
      return;
    }
    orderSessionRegistry.registerCancelRequest(sessionId, walRecord);
    compatibilityPublisher.publish(command);
  }
}

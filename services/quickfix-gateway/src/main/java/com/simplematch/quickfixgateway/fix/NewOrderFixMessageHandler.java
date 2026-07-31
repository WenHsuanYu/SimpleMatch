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

/** Owns the durable admission path for a FIX NewOrderSingle message. */
final class NewOrderFixMessageHandler {
  private final WalAppender walAppender;
  private final OrderSessionRegistry orderSessionRegistry;
  private final FixSessionMessageSender fixSessionMessageSender;
  private final FixMessageMapper fixMessageMapper;
  private final RiskSubmissionResponder riskSubmissionResponder;
  private final FixCompatibilityCommandPublisher compatibilityPublisher;
  private final CommandIdGenerator commandIdGenerator;
  private final Clock clock;

  NewOrderFixMessageHandler(
      WalAppender walAppender,
      OrderSessionRegistry orderSessionRegistry,
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper,
      RiskSubmissionResponder riskSubmissionResponder,
      FixCompatibilityCommandPublisher compatibilityPublisher,
      CommandIdGenerator commandIdGenerator,
      Clock clock) {
    this.walAppender = walAppender;
    this.orderSessionRegistry = orderSessionRegistry;
    this.fixSessionMessageSender = fixSessionMessageSender;
    this.fixMessageMapper = fixMessageMapper;
    this.riskSubmissionResponder = riskSubmissionResponder;
    this.compatibilityPublisher = compatibilityPublisher;
    this.commandIdGenerator = commandIdGenerator;
    this.clock = clock;
  }

  void handle(Message message, SessionID sessionId) throws FieldNotFound {
    final Instant now = Instant.now(clock);
    final String clOrdId = message.getString(ClOrdID.FIELD);
    final FixInboundIdentity identity =
        FixInboundIdentityValidator.validate(sessionId, clOrdId, "");
    if (!identity.valid()) {
      rejectIdentity(identity.failure(), sessionId, message, now);
      return;
    }
    final WalRecord walRecord =
        FixInboundCommandFactory.newOrder(
            message, identity, commandIdGenerator.nextCommandId(), now);
    walAppender.appendAndFlush(walRecord);
    final OrderCommand command = walRecord.toOrderCommand();
    if (!riskSubmissionResponder.submitNewOrder(command, sessionId, walRecord, now).accepted()) {
      return;
    }
    orderSessionRegistry.registerAcceptedOrder(sessionId, walRecord, 'A');
    fixSessionMessageSender.send(
        sessionId,
        fixMessageMapper.buildPendingNew(
            FixOrderSnapshot.from(walRecord),
            new FixExecutionIdentity(
                new FixExecutionIdentity.ExecutionId("E-" + walRecord.recordId()), now)));
    compatibilityPublisher.publish(command);
  }

  private void rejectIdentity(
      FixIdentityValidationFailure failure, SessionID sessionId, Message message, Instant now)
      throws FieldNotFound {
    fixSessionMessageSender.send(
        sessionId,
        fixMessageMapper.buildRejected(
            FixInboundCommandFactory.newOrderSnapshot(message),
            new FixExecutionIdentity(
                new FixExecutionIdentity.ExecutionId(
                    "RJ-" + commandIdGenerator.nextCommandId()),
                now),
            failure.reasonCode() + ": " + failure.reasonText()));
  }
}

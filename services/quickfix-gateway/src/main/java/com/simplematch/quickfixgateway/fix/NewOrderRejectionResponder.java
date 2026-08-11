package com.simplematch.quickfixgateway.fix;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;

/** Renders protocol-level rejection reports for new orders that cannot be prepared. */
final class NewOrderRejectionResponder {
  private final FixSessionMessageSender fixSessionMessageSender;
  private final FixMessageMapper fixMessageMapper;
  private final CommandIdGenerator commandIdGenerator;

  NewOrderRejectionResponder(
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper,
      CommandIdGenerator commandIdGenerator) {
    this.fixSessionMessageSender = fixSessionMessageSender;
    this.fixMessageMapper = fixMessageMapper;
    this.commandIdGenerator = commandIdGenerator;
  }

  /**
   * Sends a rejection using only the FIX fields available in the malformed inbound message.
   *
   * @param failure normalized preparation failure to render
   * @param message malformed inbound FIX message
   * @param sessionId originating FIX session
   * @throws FieldNotFound when the malformed message cannot be inspected for its available fields
   */
  void reject(NewOrderPreparationFailure failure, Message message, SessionID sessionId)
      throws FieldNotFound {
    fixSessionMessageSender.send(
        sessionId,
        fixMessageMapper.buildRejectedInboundOrder(
            message,
            new FixExecutionIdentity(
                new FixExecutionIdentity.ExecutionId("RJ-" + commandIdGenerator.nextCommandId()),
                failure.occurredAt()),
            failure.validationFailure().reasonCode()
                + ": "
                + failure.validationFailure().reasonText()));
  }
}

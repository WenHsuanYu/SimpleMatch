package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.wal.WalRecord;
import quickfix.SessionID;

/** Completes the accepted new-order path after Risk accepts durable admission. */
final class AcceptedNewOrderResponder {
  private final OrderSessionRegistry orderSessionRegistry;
  private final FixSessionMessageSender fixSessionMessageSender;
  private final FixMessageMapper fixMessageMapper;

  AcceptedNewOrderResponder(
      OrderSessionRegistry orderSessionRegistry,
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper) {
    this.orderSessionRegistry = orderSessionRegistry;
    this.fixSessionMessageSender = fixSessionMessageSender;
    this.fixMessageMapper = fixMessageMapper;
  }

  /** Registers the accepted order and sends its Pending New acknowledgement. */
  void respond(PreparedNewOrder preparedOrder, SessionID sessionId) {
    final WalRecord walRecord = preparedOrder.walRecord();
    orderSessionRegistry.registerAcceptedOrder(sessionId, walRecord, 'A');
    fixSessionMessageSender.send(
        sessionId,
        fixMessageMapper.buildPendingNew(
            FixOrderSnapshot.from(walRecord),
            new FixExecutionIdentity(
                new FixExecutionIdentity.ExecutionId("E-" + walRecord.recordId()),
                preparedOrder.preparedAt())));
  }
}

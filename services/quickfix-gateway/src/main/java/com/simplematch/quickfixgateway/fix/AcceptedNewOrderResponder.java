package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.wal.WalRecord;
import quickfix.SessionID;

/** Completes the accepted new-order path after Risk Admission accepts durable admission. */
final class AcceptedNewOrderResponder {
  private final OrderSessionRegistry orderSessionRegistry;
  private final FixSessionMessageSender fixSessionMessageSender;
  private final FixMessageMapper fixMessageMapper;
  private final FixCompatibilityCommandPublisher compatibilityPublisher;

  AcceptedNewOrderResponder(
      OrderSessionRegistry orderSessionRegistry,
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper,
      FixCompatibilityCommandPublisher compatibilityPublisher) {
    this.orderSessionRegistry = orderSessionRegistry;
    this.fixSessionMessageSender = fixSessionMessageSender;
    this.fixMessageMapper = fixMessageMapper;
    this.compatibilityPublisher = compatibilityPublisher;
  }

  /**
   * Registers the accepted order, sends Pending New, and publishes the compatibility command.
   *
   * @param preparedOrder order whose durable risk admission was accepted
   * @param sessionId originating FIX session
   */
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
    compatibilityPublisher.publish(preparedOrder.command());
  }
}

package com.simplematch.quickfixgateway.fix;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;

/** Owns the durable admission path for a FIX NewOrderSingle message. */
final class NewOrderFixMessageHandler {
  private final NewOrderCommandPreparer commandPreparer;
  private final NewOrderDurableAdmission durableAdmission;
  private final AcceptedNewOrderResponder acceptedResponder;
  private final NewOrderRejectionResponder rejectionResponder;

  NewOrderFixMessageHandler(
      NewOrderCommandPreparer commandPreparer,
      NewOrderDurableAdmission durableAdmission,
      AcceptedNewOrderResponder acceptedResponder,
      NewOrderRejectionResponder rejectionResponder) {
    this.commandPreparer = commandPreparer;
    this.durableAdmission = durableAdmission;
    this.acceptedResponder = acceptedResponder;
    this.rejectionResponder = rejectionResponder;
  }

  /**
   * Runs validation, WAL durability, risk admission, and the accepted or rejected response path.
   *
   * @param message inbound FIX NewOrderSingle message
   * @param sessionId originating FIX session
   * @throws FieldNotFound when a rejection cannot inspect the available malformed-message fields
   */
  void handle(Message message, SessionID sessionId) throws FieldNotFound {
    try {
      final PreparedNewOrder preparedOrder = commandPreparer.prepare(message, sessionId);
      if (!durableAdmission.admit(preparedOrder, sessionId).accepted()) {
        return;
      }
      acceptedResponder.respond(preparedOrder, sessionId);
    } catch (NewOrderPreparationFailure failure) {
      rejectionResponder.reject(failure, message, sessionId);
    }
  }
}

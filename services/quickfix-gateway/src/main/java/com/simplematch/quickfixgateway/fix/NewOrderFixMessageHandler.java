package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.wal.WalRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;

/** Owns the durable admission path for a FIX NewOrderSingle message. */
final class NewOrderFixMessageHandler {
  private final NewOrderCommandPreparer commandPreparer;
  private final NewOrderDurableAdmission durableAdmission;
  private final AcceptedNewOrderResponder acceptedResponder;
  private final NewOrderRejectionResponder rejectionResponder;
  private final GatewayAdmissionGate admissionGate;
  private final Clock clock;
  private final OrderSessionRegistry orderSessionRegistry;

  NewOrderFixMessageHandler(
      NewOrderCommandPreparer commandPreparer,
      NewOrderDurableAdmission durableAdmission,
      AcceptedNewOrderResponder acceptedResponder,
      NewOrderRejectionResponder rejectionResponder,
      GatewayAdmissionGate admissionGate,
      Clock clock,
      OrderSessionRegistry orderSessionRegistry) {
    this.commandPreparer = commandPreparer;
    this.durableAdmission = durableAdmission;
    this.acceptedResponder = acceptedResponder;
    this.rejectionResponder = rejectionResponder;
    this.admissionGate = admissionGate;
    this.clock = clock;
    this.orderSessionRegistry = orderSessionRegistry;
  }

  /**
   * Runs validation, WAL durability, risk admission, and the accepted or rejected response path.
   *
   * @param message inbound FIX NewOrderSingle message
   * @param sessionId originating FIX session
   * @throws FieldNotFound when a rejection cannot inspect the available malformed-message fields
   */
  void handle(Message message, SessionID sessionId) throws FieldNotFound {
    if (!admissionGate.allowsNewOrders()) {
      rejectionResponder.reject(
          new NewOrderPreparationFailure(admissionGate.newOrderFailure(), Instant.now(clock)),
          message,
          sessionId);
      return;
    }
    try {
      final PreparedNewOrder preparedOrder = commandPreparer.prepare(message, sessionId);
      final Optional<WalRecord> previous =
          orderSessionRegistry.findAdmittedOrder(
              sessionId, preparedOrder.walRecord().orderId());
      if (previous.isPresent()) {
        final WalRecord previousRecord = previous.orElseThrow();
        if (previousRecord.hasSameBusinessIntent(preparedOrder.walRecord())) {
          return;
        }
        rejectionResponder.reject(
            new NewOrderPreparationFailure(
                new FixInboundValidationFailure(
                    "DUPLICATE_CL_ORD_ID_CONFLICT",
                    "cl_ord_id already identifies a different order"),
                preparedOrder.preparedAt()),
            message,
            sessionId);
        return;
      }
      if (!durableAdmission.admit(preparedOrder, sessionId).accepted()) {
        return;
      }
      acceptedResponder.respond(preparedOrder, sessionId);
    } catch (NewOrderPreparationFailure failure) {
      rejectionResponder.reject(failure, message, sessionId);
    }
  }
}

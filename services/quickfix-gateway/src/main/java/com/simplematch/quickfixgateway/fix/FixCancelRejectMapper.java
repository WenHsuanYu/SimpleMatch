package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.matching.v1.ExecutionEvent;
import quickfix.field.ClOrdID;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrigClOrdID;
import quickfix.field.Text;
import quickfix.fix44.OrderCancelReject;

/** Renders cancel rejections from matching outcomes and inbound request correlation values. */
final class FixCancelRejectMapper {
  OrderCancelReject buildOrderCancelReject(ExecutionEvent executionEvent, OrderSessionState state) {
    final String cancelClientOrderId = executionEvent.getCancelClOrdId();
    final String originalClientOrderId = executionEvent.getOrigClOrdId();
    if (cancelClientOrderId.isBlank() || originalClientOrderId.isBlank()) {
      throw new IllegalStateException(
          "missing cancel request context for order " + state.orderId());
    }
    return build(
        executionEvent.getOrderId(),
        cancelClientOrderId,
        originalClientOrderId,
        state.lifecycle().currentOrdStatus(),
        executionEvent.getText());
  }

  OrderCancelReject buildOrderCancelReject(
      String orderId,
      String cancelClientOrderId,
      String originalClientOrderId,
      char ordStatus,
      String text) {
    return build(orderId, cancelClientOrderId, originalClientOrderId, ordStatus, text);
  }

  private OrderCancelReject build(
      String orderId,
      String cancelClientOrderId,
      String originalClientOrderId,
      char ordStatus,
      String text) {
    final OrderCancelReject reject = new OrderCancelReject();
    reject.setString(OrderID.FIELD, orderId);
    reject.setString(ClOrdID.FIELD, cancelClientOrderId);
    reject.setString(OrigClOrdID.FIELD, originalClientOrderId);
    reject.setChar(OrdStatus.FIELD, ordStatus);
    reject.setChar(CxlRejResponseTo.FIELD, '1');
    reject.setInt(CxlRejReason.FIELD, FixWireValues.mapCancelRejectReason(text));
    if (text != null && !text.isBlank()) {
      reject.setString(Text.FIELD, text);
    }
    return reject;
  }
}

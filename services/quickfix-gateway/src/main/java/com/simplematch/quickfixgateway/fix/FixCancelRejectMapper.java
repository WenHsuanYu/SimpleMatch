package com.simplematch.quickfixgateway.fix;

import quickfix.field.ClOrdID;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrigClOrdID;
import quickfix.field.Text;
import quickfix.fix44.OrderCancelReject;

/** Renders cancel rejections from explicit inbound request correlation values. */
final class FixCancelRejectMapper {
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

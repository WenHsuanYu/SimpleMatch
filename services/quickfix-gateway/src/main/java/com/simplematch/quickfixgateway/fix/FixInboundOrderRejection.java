package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.ClOrdID;
import quickfix.field.OrderQty;
import quickfix.field.Symbol;

/** Holds optional inbound order facts needed to render a rejection for malformed FIX. */
record FixInboundOrderRejection(
    String orderId, String clOrdId, String symbol, Side side, String quantity) {
  /** Extracts only available wire values so malformed input can still receive a FIX reject. */
  static FixInboundOrderRejection from(Message message) throws FieldNotFound {
    final String clOrdId = FixInboundFieldValues.optionalString(message, ClOrdID.FIELD);
    final Character side =
        FixInboundFieldValues.optionalChar(message, quickfix.field.Side.FIELD);
    return new FixInboundOrderRejection(
        clOrdId.isBlank() ? "UNKNOWN" : FixInboundCommandFactory.orderIdFor(clOrdId),
        clOrdId,
        FixInboundFieldValues.optionalString(message, Symbol.FIELD),
        side == null ? Side.SIDE_UNSPECIFIED : FixInboundFieldValues.mapSide(side),
        FixInboundFieldValues.optionalString(message, OrderQty.FIELD));
  }
}

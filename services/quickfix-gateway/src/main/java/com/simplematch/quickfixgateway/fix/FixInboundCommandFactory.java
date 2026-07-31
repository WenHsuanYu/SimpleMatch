package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.time.Instant;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.fix44.OrderCancelRequest;

/** Builds normalized WAL records and response snapshots from inbound FIX messages. */
final class FixInboundCommandFactory {
  private FixInboundCommandFactory() {}

  static WalRecord newOrder(
      Message message, FixInboundIdentity identity, String commandId, Instant now)
      throws FieldNotFound {
    final String clOrdId = message.getString(ClOrdID.FIELD);
    return new WalRecord(
        "v1",
        commandId,
        now.toEpochMilli(),
        "quickfix-gateway",
        identity.senderCompId(),
        identity.targetCompId(),
        quickfix.fix44.NewOrderSingle.MSGTYPE,
        orderIdFor(clOrdId),
        clOrdId,
        "",
        FixInboundFieldValues.optionalString(message, Account.FIELD),
        message.getString(Symbol.FIELD),
        FixInboundFieldValues.mapSide(message.getChar(quickfix.field.Side.FIELD)),
        message.getString(OrderQty.FIELD),
        FixInboundFieldValues.optionalString(message, Price.FIELD),
        FixInboundFieldValues.mapOrderType(
            FixInboundFieldValues.optionalChar(message, OrdType.FIELD)),
        FixInboundFieldValues.mapTimeInForce(
            FixInboundFieldValues.optionalChar(message, quickfix.field.TimeInForce.FIELD)),
        CommandType.COMMAND_TYPE_NEW,
        message.toString());
  }

  static WalRecord cancelOrder(
      Message message,
      FixInboundIdentity identity,
      OrderSessionState existing,
      String commandId,
      Instant now)
      throws FieldNotFound {
    final String origClOrdId = message.getString(quickfix.field.OrigClOrdID.FIELD);
    final String cancelClOrdId = message.getString(ClOrdID.FIELD);
    return new WalRecord(
        "v1",
        commandId,
        now.toEpochMilli(),
        "quickfix-gateway",
        identity.senderCompId(),
        identity.targetCompId(),
        OrderCancelRequest.MSGTYPE,
        orderIdFor(origClOrdId),
        cancelClOrdId,
        origClOrdId,
        FixInboundFieldValues.optionalString(
            message, Account.FIELD, existing == null ? "" : existing.accountId()),
        FixInboundFieldValues.optionalString(
            message, Symbol.FIELD, existing == null ? "" : existing.symbol()),
        existing == null ? Side.SIDE_UNSPECIFIED : existing.side(),
        existing == null ? "0" : existing.quantity(),
        FixInboundFieldValues.optionalString(message, Price.FIELD),
        OrderType.ORDER_TYPE_UNSPECIFIED,
        TimeInForce.TIME_IN_FORCE_UNSPECIFIED,
        CommandType.COMMAND_TYPE_CANCEL,
        message.toString());
  }

  static FixOrderSnapshot newOrderSnapshot(Message message) throws FieldNotFound {
    final String clOrdId = message.getString(ClOrdID.FIELD);
    return new FixOrderSnapshot(
        new FixOrderSnapshot.OrderId(orderIdFor(clOrdId)),
        new FixOrderSnapshot.ClientOrderId(clOrdId),
        new FixOrderSnapshot.Symbol(message.getString(Symbol.FIELD)),
        FixInboundFieldValues.mapSide(message.getChar(quickfix.field.Side.FIELD)),
        new FixOrderSnapshot.Quantity(message.getString(OrderQty.FIELD)));
  }

  static String orderIdFor(String clientOrderId) {
    return "O-" + clientOrderId;
  }
}

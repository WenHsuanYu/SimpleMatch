package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.wal.FixSessionIdentity;
import com.simplematch.quickfixgateway.wal.RawFixMessage;
import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalMetadata;
import com.simplematch.quickfixgateway.wal.WalOrderReference;
import com.simplematch.quickfixgateway.wal.WalOrderTerms;
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

/** Builds normalized WAL records and response snapshots from inbound FIX messages. */
final class FixInboundCommandFactory {
  private FixInboundCommandFactory() {}

  static WalRecord newOrder(
      Message message, FixInboundIdentity identity, String commandId, Instant now)
      throws FieldNotFound {
    final String clOrdId = message.getString(ClOrdID.FIELD);
    return new WalRecord(
        new WalMetadata("v1", commandId, now.toEpochMilli(), "quickfix-gateway"),
        new FixSessionIdentity(identity.senderCompId(), identity.targetCompId()),
        new WalOrderReference(
            orderIdFor(clOrdId),
            clOrdId,
            "",
            FixInboundFieldValues.optionalString(message, Account.FIELD)),
        new WalCommand.NewOrder(
            new WalOrderTerms(
                message.getString(Symbol.FIELD),
                FixInboundFieldValues.mapSide(message.getChar(quickfix.field.Side.FIELD)),
                message.getString(OrderQty.FIELD),
                FixInboundFieldValues.optionalString(message, Price.FIELD),
                FixInboundFieldValues.mapOrderType(
                    FixInboundFieldValues.optionalChar(message, OrdType.FIELD)),
                FixInboundFieldValues.mapTimeInForce(
                    FixInboundFieldValues.optionalChar(
                        message, quickfix.field.TimeInForce.FIELD)))),
        new RawFixMessage(message.toString()));
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
        new WalMetadata("v1", commandId, now.toEpochMilli(), "quickfix-gateway"),
        new FixSessionIdentity(identity.senderCompId(), identity.targetCompId()),
        new WalOrderReference(
            orderIdFor(origClOrdId),
            cancelClOrdId,
            origClOrdId,
            FixInboundFieldValues.optionalString(
                message, Account.FIELD, existing == null ? "" : existing.accountId())),
        new WalCommand.Cancel(),
        new RawFixMessage(message.toString()));
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

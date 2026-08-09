package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.v2.V2Identifiers;
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
import quickfix.field.Symbol;

/** Builds semantic durable commands from validated inbound FIX messages. */
final class FixInboundCommandFactory {
  private FixInboundCommandFactory() {}

  static WalRecord newOrder(
      Message message, FixInboundIdentity identity, String commandId, Instant now)
      throws FieldNotFound {
    final String clOrdId = message.getString(ClOrdID.FIELD);
    final String accountId =
        canonicalAccountId(FixInboundFieldValues.optionalString(message, Account.FIELD));
    return new WalRecord(
        new WalMetadata(
            WalMetadata.CURRENT_SCHEMA_VERSION,
            commandId,
            now.toEpochMilli(),
            "quickfix-gateway"),
        new FixSessionIdentity(identity.senderCompId(), identity.targetCompId()),
        new WalOrderReference(orderIdFor(clOrdId), clOrdId, "", accountId),
        new WalCommand.NewOrder(
            new WalOrderTerms(
                message.getString(Symbol.FIELD),
                FixInboundFieldValues.mapSide(message.getChar(quickfix.field.Side.FIELD)),
                message.getString(quickfix.field.OrderQty.FIELD),
                FixInboundFieldValues.optionalString(message, quickfix.field.Price.FIELD),
                FixInboundFieldValues.mapOrderType(
                    message.getChar(quickfix.field.OrdType.FIELD)),
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
    final String symbol =
        FixInboundFieldValues.optionalString(
            message, Symbol.FIELD, existing == null ? "" : existing.symbol());
    final Character rawSide =
        FixInboundFieldValues.optionalChar(message, quickfix.field.Side.FIELD);
    final Side side =
        rawSide == null
            ? existing == null ? Side.SIDE_UNSPECIFIED : existing.side()
            : FixInboundFieldValues.mapSide(rawSide);
    final String accountId =
        canonicalAccountId(
            FixInboundFieldValues.optionalString(
                message, Account.FIELD, existing == null ? "" : existing.accountId()));
    return new WalRecord(
        new WalMetadata(
            WalMetadata.CURRENT_SCHEMA_VERSION,
            commandId,
            now.toEpochMilli(),
            "quickfix-gateway"),
        new FixSessionIdentity(identity.senderCompId(), identity.targetCompId()),
        new WalOrderReference(orderIdFor(origClOrdId), cancelClOrdId, origClOrdId, accountId),
        new WalCommand.Cancel(symbol, side),
        new RawFixMessage(message.toString()));
  }

  static String orderIdFor(String clientOrderId) {
    if (clientOrderId == null || clientOrderId.isBlank()) {
      throw new IllegalArgumentException("cl_ord_id must not be blank");
    }
    return "O-" + clientOrderId;
  }

  private static String canonicalAccountId(String rawAccountId) {
    return V2Identifiers.AccountId.parse(rawAccountId).value().toString();
  }
}

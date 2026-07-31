package com.simplematch.quickfixgateway.wal;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;

/** Represents one durable inbound FIX command in the gateway-local write-ahead log. */
public record WalRecord(
    String schemaVersion,
    String recordId,
    long createdAtUnixMs,
    String sourceService,
    String senderCompId,
    String targetCompId,
    String messageType,
    String orderId,
    String clOrdId,
    String origClOrdId,
    String accountId,
    String symbol,
    Side side,
    String quantity,
    String price,
    OrderType orderType,
    TimeInForce tif,
    CommandType commandType,
    String rawFix) {

  /** Converts this durable record to the compatibility order-command contract. */
  public OrderCommand toOrderCommand() {
    return WalOrderCommandMapper.toOrderCommand(this);
  }
}

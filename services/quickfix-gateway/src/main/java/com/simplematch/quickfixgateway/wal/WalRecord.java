package com.simplematch.quickfixgateway.wal;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;

public record WalRecord(
    String schemaVersion,
    String recordId,
    long createdAtUnixMs,
    String sourceService,
    String sessionId,
    String messageType,
    String orderId,
    String clientOrderId,
    String originalClientOrderId,
    String accountId,
    String symbol,
    Side side,
    String quantity,
    String price,
    OrderType orderType,
    TimeInForce tif,
    CommandType commandType,
    String rawFix) {

  public OrderCommand toOrderCommand() {
    return OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion(schemaVersion)
            .setEventId(recordId)
            .setCreatedAtUnixMs(createdAtUnixMs)
            .setSourceService(sourceService)
            .build())
        .setCommandId(recordId)
        .setOrderId(orderId)
        .setAccountId(accountId == null ? "" : accountId)
        .setSessionId(sessionId == null ? "" : sessionId)
        .setClientOrderId(clientOrderId == null ? "" : clientOrderId)
        .setSymbol(symbol == null ? "" : symbol)
        .setSide(side == null ? Side.SIDE_UNSPECIFIED : side)
        .setQuantity(quantity == null ? "" : quantity)
        .setPrice(price == null ? "" : price)
        .setOrderType(orderType == null ? OrderType.ORDER_TYPE_UNSPECIFIED : orderType)
        .setTif(tif == null ? TimeInForce.TIME_IN_FORCE_UNSPECIFIED : tif)
        .setCommandType(commandType == null ? CommandType.COMMAND_TYPE_UNSPECIFIED : commandType)
        .setOriginalClientOrderId(originalClientOrderId == null ? "" : originalClientOrderId)
        .build();
  }
}
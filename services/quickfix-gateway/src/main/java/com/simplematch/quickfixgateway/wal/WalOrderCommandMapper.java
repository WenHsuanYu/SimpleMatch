package com.simplematch.quickfixgateway.wal;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;

/** Maps WAL records to the v1 compatibility command while normalizing nullable legacy fields. */
final class WalOrderCommandMapper {
  private WalOrderCommandMapper() {}

  static OrderCommand toOrderCommand(WalRecord record) {
    return OrderCommand.newBuilder()
        .setMetadata(
            EventMetadata.newBuilder()
                .setSchemaVersion(record.schemaVersion())
                .setEventId(record.recordId())
                .setCreatedAtUnixMs(record.createdAtUnixMs())
                .setSourceService(record.sourceService())
                .build())
        .setCommandId(record.recordId())
        .setOrderId(record.orderId())
        .setAccountId(orEmpty(record.accountId()))
        .setSenderCompId(orEmpty(record.senderCompId()))
        .setTargetCompId(orEmpty(record.targetCompId()))
        .setClOrdId(orEmpty(record.clOrdId()))
        .setSymbol(orEmpty(record.symbol()))
        .setSide(orUnspecified(record.side()))
        .setQuantity(orEmpty(record.quantity()))
        .setPrice(orEmpty(record.price()))
        .setOrderType(orUnspecified(record.orderType()))
        .setTif(orUnspecified(record.tif()))
        .setCommandType(orUnspecified(record.commandType()))
        .setOrigClOrdId(orEmpty(record.origClOrdId()))
        .build();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static Side orUnspecified(Side value) {
    return value == null ? Side.SIDE_UNSPECIFIED : value;
  }

  private static OrderType orUnspecified(OrderType value) {
    return value == null ? OrderType.ORDER_TYPE_UNSPECIFIED : value;
  }

  private static TimeInForce orUnspecified(TimeInForce value) {
    return value == null ? TimeInForce.TIME_IN_FORCE_UNSPECIFIED : value;
  }

  private static CommandType orUnspecified(CommandType value) {
    return value == null ? CommandType.COMMAND_TYPE_UNSPECIFIED : value;
  }
}

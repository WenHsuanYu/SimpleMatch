package com.simplematch.quickfixgateway.wal;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.orders.v1.OrderCommand;

/** Maps WAL records to the v1 compatibility command while normalizing nullable legacy fields. */
final class WalOrderCommandMapper {
  private WalOrderCommandMapper() {}

  static OrderCommand toOrderCommand(WalRecord record) {
    final OrderCommand.Builder builder =
        OrderCommand.newBuilder()
            .setMetadata(
                EventMetadata.newBuilder()
                    .setSchemaVersion("v1")
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
            .setCommandType(record.commandType())
            .setOrigClOrdId(orEmpty(record.origClOrdId()))
            .setSymbol(record.symbol())
            .setSide(record.side());
    if (record.command() instanceof WalCommand.NewOrder newOrder) {
      final WalOrderTerms terms = newOrder.terms();
      builder
          .setQuantity(terms.quantity())
          .setPrice(terms.price())
          .setOrderType(terms.orderType())
          .setTif(terms.tif());
    }
    return builder.build();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}

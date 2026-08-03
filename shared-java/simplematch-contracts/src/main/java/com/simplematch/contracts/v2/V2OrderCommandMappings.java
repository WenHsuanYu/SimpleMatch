package com.simplematch.contracts.v2;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;

/** Owns reverse field mappings from the typed v2 order contracts to v1 wire fields. */
final class V2OrderCommandMappings {
  OrderCommand.Builder baseV1Command(NewOrderCommand command) {
    return OrderCommand.newBuilder()
        .setMetadata(toV1Metadata(command.getMetadata()))
        .setCommandId(command.getCommandId())
        .setOrderId(command.getOrderId())
        .setAccountId(command.getAccountId())
        .setSenderCompId(command.getSenderCompId())
        .setTargetCompId(command.getTargetCompId())
        .setClOrdId(command.getClOrdId())
        .setSymbol(command.getInstrument().getSymbol());
  }

  OrderCommand.Builder baseV1Command(CancelOrderCommand command) {
    return OrderCommand.newBuilder()
        .setMetadata(toV1Metadata(command.getMetadata()))
        .setCommandId(command.getCommandId())
        .setOrderId(command.getOrderId())
        .setAccountId(command.getAccountId())
        .setSenderCompId(command.getSenderCompId())
        .setTargetCompId(command.getTargetCompId())
        .setClOrdId(command.getClOrdId())
        .setSymbol(command.getInstrument().getSymbol());
  }

  private EventMetadata.Builder toV1Metadata(
      com.simplematch.contracts.common.v2.EventMetadata metadata) {
    return EventMetadata.newBuilder()
        .setSchemaVersion("v1")
        .setEventId(metadata.getEventId())
        .setCreatedAtUnixMs(metadata.getCreatedAtUnixMs())
        .setSourceService(metadata.getSourceService());
  }

  com.simplematch.contracts.common.v1.Side toV1Side(
      com.simplematch.contracts.common.v2.Side side) {
    return switch (side) {
      case SIDE_BUY -> com.simplematch.contracts.common.v1.Side.SIDE_BUY;
      case SIDE_SELL -> com.simplematch.contracts.common.v1.Side.SIDE_SELL;
      default -> com.simplematch.contracts.common.v1.Side.SIDE_UNSPECIFIED;
    };
  }

  com.simplematch.contracts.common.v1.OrderType toV1OrderType(
      com.simplematch.contracts.common.v2.OrderType orderType) {
    return switch (orderType) {
      case ORDER_TYPE_LIMIT -> com.simplematch.contracts.common.v1.OrderType.ORDER_TYPE_LIMIT;
      case ORDER_TYPE_MARKET -> com.simplematch.contracts.common.v1.OrderType.ORDER_TYPE_MARKET;
      default -> com.simplematch.contracts.common.v1.OrderType.ORDER_TYPE_UNSPECIFIED;
    };
  }

  com.simplematch.contracts.common.v1.TimeInForce toV1Tif(
      com.simplematch.contracts.common.v2.TimeInForce tif) {
    return switch (tif) {
      case TIME_IN_FORCE_ROD -> com.simplematch.contracts.common.v1.TimeInForce.TIME_IN_FORCE_ROD;
      case TIME_IN_FORCE_IOC -> com.simplematch.contracts.common.v1.TimeInForce.TIME_IN_FORCE_IOC;
      case TIME_IN_FORCE_FOK -> com.simplematch.contracts.common.v1.TimeInForce.TIME_IN_FORCE_FOK;
      default -> com.simplematch.contracts.common.v1.TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    };
  }
}

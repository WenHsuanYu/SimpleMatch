package com.simplematch.contracts.v2;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.orders.v1.OrderCommand;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Owns field-level conversion details for the v1 order compatibility seam. */
final class V1OrderCommandMappings {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  private final VenueMic ingressVenue;

  V1OrderCommandMappings(VenueMic ingressVenue) {
    this.ingressVenue = Objects.requireNonNull(ingressVenue, "ingress venue is required");
  }

  com.simplematch.contracts.common.v2.EventMetadata toV2Metadata(OrderCommand command) {
    final EventMetadata metadata = command.getMetadata();
    if (!command.hasMetadata() || !"v1".equals(metadata.getSchemaVersion())) {
      throw new DomainValidationException("v1 metadata.schema_version must be v1");
    }
    return com.simplematch.contracts.common.v2.EventMetadata.newBuilder()
        .setSchemaVersion("v2")
        .setEventId(metadata.getEventId())
        .setCreatedAtUnixMs(metadata.getCreatedAtUnixMs())
        .setSourceService(metadata.getSourceService())
        .setCorrelationId(command.getCommandId())
        .build();
  }

  com.simplematch.contracts.common.v2.VenueInstrument instrument(String symbol) {
    return com.simplematch.contracts.common.v2.VenueInstrument.newBuilder()
        .setSymbol(symbol)
        .setVenueMic(ingressVenue.name())
        .build();
  }

  com.simplematch.contracts.common.v2.TradingDay tradingDay(OrderCommand command) {
    final long createdAt = command.getMetadata().getCreatedAtUnixMs();
    if (createdAt <= 0) {
      throw new DomainValidationException("created_at_unix_ms must be positive");
    }
    return com.simplematch.contracts.common.v2.TradingDay.newBuilder()
        .setIsoDate(Instant.ofEpochMilli(createdAt).atZone(TAIPEI).toLocalDate().toString())
        .build();
  }

  long estimatedNotional(TwdPrice price, ShareQuantity quantity) {
    try {
      return Math.multiplyExact(price.units(), quantity.shares());
    } catch (ArithmeticException exception) {
      throw new DomainValidationException("estimated_notional exceeds signed 64-bit range");
    }
  }

  com.simplematch.contracts.common.v2.Side toV2Side(
      com.simplematch.contracts.common.v1.Side side) {
    return switch (side) {
      case SIDE_BUY -> com.simplematch.contracts.common.v2.Side.SIDE_BUY;
      case SIDE_SELL -> com.simplematch.contracts.common.v2.Side.SIDE_SELL;
      default -> com.simplematch.contracts.common.v2.Side.SIDE_UNSPECIFIED;
    };
  }

  com.simplematch.contracts.common.v2.OrderType toV2OrderType(
      com.simplematch.contracts.common.v1.OrderType orderType) {
    return switch (orderType) {
      case ORDER_TYPE_LIMIT -> com.simplematch.contracts.common.v2.OrderType.ORDER_TYPE_LIMIT;
      case ORDER_TYPE_MARKET -> com.simplematch.contracts.common.v2.OrderType.ORDER_TYPE_MARKET;
      default -> com.simplematch.contracts.common.v2.OrderType.ORDER_TYPE_UNSPECIFIED;
    };
  }

  com.simplematch.contracts.common.v2.TimeInForce toV2Tif(
      com.simplematch.contracts.common.v1.TimeInForce tif) {
    return switch (tif) {
      case TIME_IN_FORCE_ROD -> com.simplematch.contracts.common.v2.TimeInForce.TIME_IN_FORCE_ROD;
      case TIME_IN_FORCE_IOC -> com.simplematch.contracts.common.v2.TimeInForce.TIME_IN_FORCE_IOC;
      case TIME_IN_FORCE_FOK -> com.simplematch.contracts.common.v2.TimeInForce.TIME_IN_FORCE_FOK;
      default -> com.simplematch.contracts.common.v2.TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    };
  }

}

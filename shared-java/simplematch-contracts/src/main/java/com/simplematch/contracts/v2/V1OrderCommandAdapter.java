package com.simplematch.contracts.v2;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Converts representable v1 order commands to and from the additive v2 contracts. */
@SuppressWarnings("PMD.TooManyMethods") // V1 wire compatibility seam; retire with v1 order ingress.
public final class V1OrderCommandAdapter {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  private final VenueMic ingressVenue;
  private final V2ContractValidator validator;

  /** Creates an adapter for v1 ingress whose venue is supplied by the deployment boundary. */
  public V1OrderCommandAdapter(VenueMic ingressVenue) {
    this(ingressVenue, new V2ContractValidator());
  }

  V1OrderCommandAdapter(VenueMic ingressVenue, V2ContractValidator validator) {
    this.ingressVenue = Objects.requireNonNull(ingressVenue, "ingress venue is required");
    this.validator = Objects.requireNonNull(validator, "validator is required");
  }

  /** Converts a v1 new-order command to the typed v2 ingress contract. */
  public NewOrderCommand toNewOrder(OrderCommand command) {
    requireType(command, CommandType.COMMAND_TYPE_NEW);
    final NewOrderCommand.Builder builder =
        NewOrderCommand.newBuilder()
            .setMetadata(toV2Metadata(command))
            .setCommandId(command.getCommandId())
            .setOrderId(command.getOrderId())
            .setAccountId(command.getAccountId())
            .setInstrument(instrument(command.getSymbol()))
            .setSide(toV2Side(command.getSide()))
            .setQuantity(
                com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder()
                    .setShares(ShareQuantity.parse(command.getQuantity()).shares()))
            .setOrderType(toV2OrderType(command.getOrderType()))
            .setTif(toV2Tif(command.getTif()))
            .setCurrency(com.simplematch.contracts.common.v2.Currency.CURRENCY_TWD)
            .setTradingDay(tradingDay(command))
            .setSessionState(
                com.simplematch.contracts.common.v2.SessionState.SESSION_STATE_CONTINUOUS)
            .setSenderCompId(command.getSenderCompId())
            .setTargetCompId(command.getTargetCompId())
            .setClOrdId(command.getClOrdId());
    if (command.getOrderType() == com.simplematch.contracts.common.v1.OrderType.ORDER_TYPE_LIMIT) {
      final TwdPrice price = TwdPrice.ofDecimal(command.getPrice());
      builder.setLimitPrice(
          com.simplematch.contracts.common.v2.TwdPrice.newBuilder().setUnits(price.units()));
      builder.setEstimatedNotional(
          com.simplematch.contracts.common.v2.TwdNotional.newBuilder()
              .setUnits(estimatedNotional(price, ShareQuantity.parse(command.getQuantity()))));
    }
    final NewOrderCommand converted = builder.build();
    validator.validate(converted);
    return converted;
  }

  /** Converts a v1 cancel-order command to the typed v2 ingress contract. */
  public CancelOrderCommand toCancelOrder(OrderCommand command) {
    requireType(command, CommandType.COMMAND_TYPE_CANCEL);
    final CancelOrderCommand converted =
        CancelOrderCommand.newBuilder()
            .setMetadata(toV2Metadata(command))
            .setCommandId(command.getCommandId())
            .setOrderId(command.getOrderId())
            .setAccountId(command.getAccountId())
            .setInstrument(instrument(command.getSymbol()))
            .setTradingDay(tradingDay(command))
            .setSessionState(
                com.simplematch.contracts.common.v2.SessionState.SESSION_STATE_CONTINUOUS)
            .setSenderCompId(command.getSenderCompId())
            .setTargetCompId(command.getTargetCompId())
            .setClOrdId(command.getClOrdId())
            .setOrigClOrdId(command.getOrigClOrdId())
            .setSide(toV2Side(command.getSide()))
            .build();
    validator.validate(converted);
    return converted;
  }

  /** Converts a v2 new-order command back to its v1 wire representation. */
  public OrderCommand toV1(NewOrderCommand command) {
    validator.validate(command);
    final OrderCommand.Builder builder =
        baseV1Command(command)
            .setSide(toV1Side(command.getSide()))
            .setQuantity(Long.toString(command.getQuantity().getShares()))
            .setOrderType(toV1OrderType(command.getOrderType()))
            .setTif(toV1Tif(command.getTif()))
            .setCommandType(CommandType.COMMAND_TYPE_NEW);
    if (command.getOrderType() == com.simplematch.contracts.common.v2.OrderType.ORDER_TYPE_LIMIT) {
      builder.setPrice(new TwdPrice(command.getLimitPrice().getUnits()).toDecimalString());
    }
    return builder.build();
  }

  /** Converts a v2 cancel-order command back to its v1 wire representation. */
  public OrderCommand toV1(CancelOrderCommand command) {
    validator.validate(command);
    return baseV1Command(command)
        .setOrigClOrdId(command.getOrigClOrdId())
        .setSide(toV1Side(command.getSide()))
        .setQuantity("0")
        .setOrderType(com.simplematch.contracts.common.v1.OrderType.ORDER_TYPE_UNSPECIFIED)
        .setTif(com.simplematch.contracts.common.v1.TimeInForce.TIME_IN_FORCE_UNSPECIFIED)
        .setCommandType(CommandType.COMMAND_TYPE_CANCEL)
        .build();
  }

  private void requireType(OrderCommand command, CommandType expectedType) {
    if (command == null || command.getCommandType() != expectedType) {
      throw new DomainValidationException("v1 command_type must be " + expectedType.name());
    }
  }

  private com.simplematch.contracts.common.v2.EventMetadata toV2Metadata(OrderCommand command) {
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

  private com.simplematch.contracts.common.v2.VenueInstrument instrument(String symbol) {
    return com.simplematch.contracts.common.v2.VenueInstrument.newBuilder()
        .setSymbol(symbol)
        .setVenueMic(ingressVenue.name())
        .build();
  }

  private com.simplematch.contracts.common.v2.TradingDay tradingDay(OrderCommand command) {
    final long createdAt = command.getMetadata().getCreatedAtUnixMs();
    if (createdAt <= 0) {
      throw new DomainValidationException("created_at_unix_ms must be positive");
    }
    return com.simplematch.contracts.common.v2.TradingDay.newBuilder()
        .setIsoDate(Instant.ofEpochMilli(createdAt).atZone(TAIPEI).toLocalDate().toString())
        .build();
  }

  private OrderCommand.Builder baseV1Command(NewOrderCommand command) {
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

  private OrderCommand.Builder baseV1Command(CancelOrderCommand command) {
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

  private long estimatedNotional(TwdPrice price, ShareQuantity quantity) {
    try {
      return Math.multiplyExact(price.units(), quantity.shares());
    } catch (ArithmeticException exception) {
      throw new DomainValidationException("estimated_notional exceeds signed 64-bit range");
    }
  }

  private com.simplematch.contracts.common.v2.Side toV2Side(
      com.simplematch.contracts.common.v1.Side side) {
    return switch (side) {
      case SIDE_BUY -> com.simplematch.contracts.common.v2.Side.SIDE_BUY;
      case SIDE_SELL -> com.simplematch.contracts.common.v2.Side.SIDE_SELL;
      default -> com.simplematch.contracts.common.v2.Side.SIDE_UNSPECIFIED;
    };
  }

  private com.simplematch.contracts.common.v2.OrderType toV2OrderType(
      com.simplematch.contracts.common.v1.OrderType orderType) {
    return switch (orderType) {
      case ORDER_TYPE_LIMIT -> com.simplematch.contracts.common.v2.OrderType.ORDER_TYPE_LIMIT;
      case ORDER_TYPE_MARKET -> com.simplematch.contracts.common.v2.OrderType.ORDER_TYPE_MARKET;
      default -> com.simplematch.contracts.common.v2.OrderType.ORDER_TYPE_UNSPECIFIED;
    };
  }

  private com.simplematch.contracts.common.v2.TimeInForce toV2Tif(
      com.simplematch.contracts.common.v1.TimeInForce tif) {
    return switch (tif) {
      case TIME_IN_FORCE_ROD -> com.simplematch.contracts.common.v2.TimeInForce.TIME_IN_FORCE_ROD;
      case TIME_IN_FORCE_IOC -> com.simplematch.contracts.common.v2.TimeInForce.TIME_IN_FORCE_IOC;
      case TIME_IN_FORCE_FOK -> com.simplematch.contracts.common.v2.TimeInForce.TIME_IN_FORCE_FOK;
      default -> com.simplematch.contracts.common.v2.TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    };
  }

  private com.simplematch.contracts.common.v1.Side toV1Side(
      com.simplematch.contracts.common.v2.Side side) {
    return switch (side) {
      case SIDE_BUY -> com.simplematch.contracts.common.v1.Side.SIDE_BUY;
      case SIDE_SELL -> com.simplematch.contracts.common.v1.Side.SIDE_SELL;
      default -> com.simplematch.contracts.common.v1.Side.SIDE_UNSPECIFIED;
    };
  }

  private com.simplematch.contracts.common.v1.OrderType toV1OrderType(
      com.simplematch.contracts.common.v2.OrderType orderType) {
    return switch (orderType) {
      case ORDER_TYPE_LIMIT -> com.simplematch.contracts.common.v1.OrderType.ORDER_TYPE_LIMIT;
      case ORDER_TYPE_MARKET -> com.simplematch.contracts.common.v1.OrderType.ORDER_TYPE_MARKET;
      default -> com.simplematch.contracts.common.v1.OrderType.ORDER_TYPE_UNSPECIFIED;
    };
  }

  private com.simplematch.contracts.common.v1.TimeInForce toV1Tif(
      com.simplematch.contracts.common.v2.TimeInForce tif) {
    return switch (tif) {
      case TIME_IN_FORCE_ROD -> com.simplematch.contracts.common.v1.TimeInForce.TIME_IN_FORCE_ROD;
      case TIME_IN_FORCE_IOC -> com.simplematch.contracts.common.v1.TimeInForce.TIME_IN_FORCE_IOC;
      case TIME_IN_FORCE_FOK -> com.simplematch.contracts.common.v1.TimeInForce.TIME_IN_FORCE_FOK;
      default -> com.simplematch.contracts.common.v1.TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    };
  }
}

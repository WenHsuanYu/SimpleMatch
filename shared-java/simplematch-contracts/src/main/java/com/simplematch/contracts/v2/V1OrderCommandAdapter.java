package com.simplematch.contracts.v2;

import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import java.util.Objects;

/** Converts representable v1 order commands to and from the additive v2 contracts. */
public final class V1OrderCommandAdapter {
  private final V1OrderCommandMappings v1Mappings;
  private final V2OrderCommandMappings v2Mappings;
  private final V2ContractValidator validator;

  /** Creates an adapter for v1 ingress whose venue is supplied by the deployment boundary. */
  public V1OrderCommandAdapter(VenueMic ingressVenue) {
    this(ingressVenue, new V2ContractValidator());
  }

  V1OrderCommandAdapter(VenueMic ingressVenue, V2ContractValidator validator) {
    v1Mappings = new V1OrderCommandMappings(ingressVenue);
    v2Mappings = new V2OrderCommandMappings();
    this.validator = Objects.requireNonNull(validator, "validator is required");
  }

  /** Converts a v1 new-order command to the typed v2 ingress contract. */
  public NewOrderCommand toNewOrder(OrderCommand command) {
    requireType(command, CommandType.COMMAND_TYPE_NEW);
    final NewOrderCommand.Builder builder =
        NewOrderCommand.newBuilder()
            .setMetadata(v1Mappings.toV2Metadata(command))
            .setCommandId(command.getCommandId())
            .setOrderId(command.getOrderId())
            .setAccountId(command.getAccountId())
            .setInstrument(v1Mappings.instrument(command.getSymbol()))
            .setSide(v1Mappings.toV2Side(command.getSide()))
            .setQuantity(
                com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder()
                    .setShares(ShareQuantity.parse(command.getQuantity()).shares()))
            .setOrderType(v1Mappings.toV2OrderType(command.getOrderType()))
            .setTif(v1Mappings.toV2Tif(command.getTif()))
            .setCurrency(com.simplematch.contracts.common.v2.Currency.CURRENCY_TWD)
            .setTradingDay(v1Mappings.tradingDay(command))
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
              .setUnits(
                  v1Mappings.estimatedNotional(price, ShareQuantity.parse(command.getQuantity()))));
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
            .setMetadata(v1Mappings.toV2Metadata(command))
            .setCommandId(command.getCommandId())
            .setOrderId(command.getOrderId())
            .setAccountId(command.getAccountId())
            .setInstrument(v1Mappings.instrument(command.getSymbol()))
            .setTradingDay(v1Mappings.tradingDay(command))
            .setSessionState(
                com.simplematch.contracts.common.v2.SessionState.SESSION_STATE_CONTINUOUS)
            .setSenderCompId(command.getSenderCompId())
            .setTargetCompId(command.getTargetCompId())
            .setClOrdId(command.getClOrdId())
            .setOrigClOrdId(command.getOrigClOrdId())
            .setSide(v1Mappings.toV2Side(command.getSide()))
            .build();
    validator.validate(converted);
    return converted;
  }

  /** Converts a v2 new-order command back to its v1 wire representation. */
  public OrderCommand toV1(NewOrderCommand command) {
    validator.validate(command);
    final OrderCommand.Builder builder =
        v2Mappings
            .baseV1Command(command)
            .setSide(v2Mappings.toV1Side(command.getSide()))
            .setQuantity(Long.toString(command.getQuantity().getShares()))
            .setOrderType(v2Mappings.toV1OrderType(command.getOrderType()))
            .setTif(v2Mappings.toV1Tif(command.getTif()))
            .setCommandType(CommandType.COMMAND_TYPE_NEW);
    if (command.getOrderType() == com.simplematch.contracts.common.v2.OrderType.ORDER_TYPE_LIMIT) {
      builder.setPrice(new TwdPrice(command.getLimitPrice().getUnits()).toDecimalString());
    }
    return builder.build();
  }

  /** Converts a v2 cancel-order command back to its v1 wire representation. */
  public OrderCommand toV1(CancelOrderCommand command) {
    validator.validate(command);
    return v2Mappings
        .baseV1Command(command)
        .setOrigClOrdId(command.getOrigClOrdId())
        .setSide(v2Mappings.toV1Side(command.getSide()))
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

}

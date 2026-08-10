package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.TradingDay;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.v2.DomainValidationException;
import com.simplematch.contracts.v2.ShareQuantity;
import com.simplematch.contracts.v2.TwdPrice;
import com.simplematch.contracts.v2.V2ContractValidator;
import com.simplematch.contracts.v2.VenueMic;
import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalOrderTerms;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Maps durable Gateway commands directly to the production v2 Risk admission contract. */
public final class RiskCommandMapper {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  private final VenueMic ingressVenue;
  private final RiskOrderIdentityDeriver orderIdentityDeriver;
  private final V2ContractValidator validator;

  /** Creates a direct WAL-to-v2 mapper for the configured ingress venue. */
  public RiskCommandMapper(VenueMic ingressVenue, RiskOrderIdentityDeriver orderIdentityDeriver) {
    this(ingressVenue, orderIdentityDeriver, new V2ContractValidator());
  }

  RiskCommandMapper(
      VenueMic ingressVenue,
      RiskOrderIdentityDeriver orderIdentityDeriver,
      V2ContractValidator validator) {
    this.ingressVenue = Objects.requireNonNull(ingressVenue, "ingressVenue");
    this.orderIdentityDeriver =
        Objects.requireNonNull(orderIdentityDeriver, "orderIdentityDeriver");
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  /** Maps a durable new-order record directly to a validated v2 Risk command. */
  public NewOrderCommand toNewOrder(WalRecord record) {
    if (!(record.command() instanceof WalCommand.NewOrder newOrder)) {
      throw new DomainValidationException("WAL command must be a new order");
    }
    final LocalDate tradingDay = tradingDay(record);
    final WalOrderTerms terms = newOrder.terms();
    final ShareQuantity quantity = ShareQuantity.parse(terms.quantity());
    final NewOrderCommand.Builder builder =
        NewOrderCommand.newBuilder()
            .setMetadata(metadata(record))
            .setCommandId(record.recordId())
            .setOrderId(orderIdentityDeriver.derive(record, tradingDay))
            .setAccountId(record.accountId())
            .setInstrument(instrument(record.symbol()))
            .setSide(record.side())
            .setQuantity(
                com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder()
                    .setShares(quantity.shares()))
            .setOrderType(record.orderType())
            .setTif(record.tif())
            .setCurrency(Currency.CURRENCY_TWD)
            .setTradingDay(TradingDay.newBuilder().setIsoDate(tradingDay.toString()))
            .setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
            .setSenderCompId(record.senderCompId())
            .setTargetCompId(record.targetCompId())
            .setClOrdId(record.clOrdId());
    if (record.orderType()
        == com.simplematch.contracts.common.v2.OrderType.ORDER_TYPE_LIMIT) {
      final TwdPrice price = TwdPrice.ofDecimal(record.price());
      builder.setLimitPrice(
          com.simplematch.contracts.common.v2.TwdPrice.newBuilder().setUnits(price.units()));
      builder.setEstimatedNotional(
          com.simplematch.contracts.common.v2.TwdNotional.newBuilder()
              .setUnits(estimatedNotional(price, quantity)));
    }
    final NewOrderCommand mapped = builder.build();
    validator.validate(mapped);
    return mapped;
  }

  /** Maps a durable cancellation record directly to a validated v2 Risk command. */
  public CancelOrderCommand toCancelOrder(WalRecord record) {
    if (!(record.command() instanceof WalCommand.Cancel cancel)) {
      throw new DomainValidationException("WAL command must be a cancellation");
    }
    if (!cancel.hasOrderContext()) {
      throw new DomainValidationException("cancel WAL requires symbol and side for v2 admission");
    }
    final LocalDate tradingDay = tradingDay(record);
    final CancelOrderCommand mapped =
        CancelOrderCommand.newBuilder()
            .setMetadata(metadata(record))
            .setCommandId(record.recordId())
            .setOrderId(orderIdentityDeriver.derive(record, tradingDay))
            .setAccountId(record.accountId())
            .setInstrument(instrument(record.symbol()))
            .setTradingDay(TradingDay.newBuilder().setIsoDate(tradingDay.toString()))
            .setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
            .setSenderCompId(record.senderCompId())
            .setTargetCompId(record.targetCompId())
            .setClOrdId(record.clOrdId())
            .setOrigClOrdId(record.origClOrdId())
            .setSide(record.side())
            .build();
    validator.validate(mapped);
    return mapped;
  }

  private EventMetadata metadata(WalRecord record) {
    return EventMetadata.newBuilder()
        .setSchemaVersion("v2")
        .setEventId(record.recordId())
        .setCreatedAtUnixMs(record.createdAtUnixMs())
        .setSourceService(record.sourceService())
        .setCorrelationId(record.recordId())
        .build();
  }

  private VenueInstrument instrument(String symbol) {
    return VenueInstrument.newBuilder().setSymbol(symbol).setVenueMic(ingressVenue.name()).build();
  }

  private LocalDate tradingDay(WalRecord record) {
    if (record.createdAtUnixMs() <= 0) {
      throw new DomainValidationException("created_at_unix_ms must be positive");
    }
    return Instant.ofEpochMilli(record.createdAtUnixMs()).atZone(TAIPEI).toLocalDate();
  }

  private long estimatedNotional(TwdPrice price, ShareQuantity quantity) {
    try {
      return Math.multiplyExact(price.units(), quantity.shares());
    } catch (ArithmeticException exception) {
      throw new DomainValidationException("estimated_notional exceeds signed 64-bit range");
    }
  }
}

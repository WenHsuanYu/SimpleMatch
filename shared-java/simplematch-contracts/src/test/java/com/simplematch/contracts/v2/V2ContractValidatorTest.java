package com.simplematch.contracts.v2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.common.v2.TradingDay;
import com.simplematch.contracts.common.v2.TwdNotional;
import com.simplematch.contracts.common.v2.TwdPrice;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.orders.v2.ShareQuantity;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V2ContractValidatorTest {
  private final V2ContractValidator validator = new V2ContractValidator();

  @DisplayName("a complete Taiwan limit order is valid at the v2 command seam")
  @Test
  void acceptsCompleteTaiwanLimitOrder() {
    assertDoesNotThrow(() -> validator.validate(newOrder()));
  }

  @DisplayName(
      "invalid identifiers, timestamps, currency, quantities, and prices fail at the v2 command seam")
  @Test
  void rejectsInvalidBusinessValues() {
    assertThrows(
        DomainValidationException.class,
        () -> validator.validate(newOrder().toBuilder().setCommandId("not-a-uuid").build()));
    assertThrows(
        DomainValidationException.class,
        () ->
            validator.validate(
                newOrder().toBuilder()
                    .setMetadata(metadata().setCreatedAtUnixMs(0).build())
                    .build()));
    assertThrows(
        DomainValidationException.class,
        () ->
            validator.validate(
                newOrder().toBuilder().setCurrency(Currency.CURRENCY_UNSPECIFIED).build()));
    assertThrows(
        DomainValidationException.class,
        () ->
            validator.validate(
                newOrder().toBuilder()
                    .setQuantity(ShareQuantity.newBuilder().setShares(0).build())
                    .build()));
    assertThrows(
        DomainValidationException.class,
        () ->
            validator.validate(
                newOrder().toBuilder()
                    .setLimitPrice(TwdPrice.newBuilder().setUnits(0).build())
                    .build()));
  }

  private static NewOrderCommand newOrder() {
    return NewOrderCommand.newBuilder()
        .setMetadata(metadata())
        .setCommandId(uuid())
        .setOrderId(uuid())
        .setAccountId(uuid())
        .setInstrument(VenueInstrument.newBuilder().setSymbol("2330").setVenueMic("XTAI"))
        .setSide(Side.SIDE_BUY)
        .setQuantity(ShareQuantity.newBuilder().setShares(1_000))
        .setLimitPrice(TwdPrice.newBuilder().setUnits(1_234_567))
        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
        .setTif(TimeInForce.TIME_IN_FORCE_ROD)
        .setCurrency(Currency.CURRENCY_TWD)
        .setTradingDay(TradingDay.newBuilder().setIsoDate("2026-07-27"))
        .setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
        .setRoutingSnapshotId(uuid())
        .setEstimatedNotional(TwdNotional.newBuilder().setUnits(1_234_567_000L))
        .setSenderCompId("CLIENT")
        .setTargetCompId("SIMPLEMATCH")
        .setClOrdId("CLIENT-1")
        .build();
  }

  private static EventMetadata.Builder metadata() {
    return EventMetadata.newBuilder()
        .setSchemaVersion("v2")
        .setEventId(uuid())
        .setCreatedAtUnixMs(1_785_490_400_000L)
        .setSourceService("quickfix-gateway")
        .setCorrelationId(uuid());
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }
}

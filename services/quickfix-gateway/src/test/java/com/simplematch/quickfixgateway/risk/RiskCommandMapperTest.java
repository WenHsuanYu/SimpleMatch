package com.simplematch.quickfixgateway.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.v2.VenueMic;
import com.simplematch.quickfixgateway.wal.FixSessionIdentity;
import com.simplematch.quickfixgateway.wal.RawFixMessage;
import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalMetadata;
import com.simplematch.quickfixgateway.wal.WalOrderReference;
import com.simplematch.quickfixgateway.wal.WalOrderTerms;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskCommandMapperTest {
  private static final Instant CREATED_AT = Instant.parse("2024-03-27T08:09:10.123Z");
  private static final LocalDate TRADING_DAY = LocalDate.of(2024, 3, 27);
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

  private final RiskCommandMapper mapper =
      new RiskCommandMapper(
          VenueMic.parse("XTAI"), new RiskOrderIdentityDeriver(TRADING_DAY));

  @Test
  void newAndCancelShareStableInternalOrderIdentity() {
    final WalRecord newOrder = newOrder(CREATED_AT);
    final WalRecord cancel = cancel(CREATED_AT);

    final NewOrderCommand mappedNewOrder = mapper.toNewOrder(newOrder);
    final CancelOrderCommand mappedCancel = mapper.toCancelOrder(cancel);

    assertThat(mappedCancel.getOrderId()).isEqualTo(mappedNewOrder.getOrderId());
    assertThat(UUID.fromString(mappedNewOrder.getOrderId())).isNotNull();
    assertThat(newOrder.orderId()).isEqualTo("O-C1");
    assertThat(cancel.orderId()).isEqualTo("O-C1");
    assertThat(mappedNewOrder.getCommandId()).isEqualTo(newOrder.recordId());
    assertThat(mappedCancel.getCommandId()).isEqualTo(cancel.recordId());
  }

  @Test
  void tradingDaySeparatesReusedClientOrderIds() {
    final RiskCommandMapper nextDayMapper =
        new RiskCommandMapper(
            VenueMic.parse("XTAI"),
            new RiskOrderIdentityDeriver(TRADING_DAY.plusDays(1)));
    final String firstDay = mapper.toNewOrder(newOrder(CREATED_AT)).getOrderId();
    final String nextDay =
        nextDayMapper.toNewOrder(newOrder(CREATED_AT.plus(1, ChronoUnit.DAYS))).getOrderId();

    assertThat(nextDay).isNotEqualTo(firstDay);
  }

  @Test
  void configuredSessionDayRemainsAuthoritativeAfterTheWallClockAdvances() {
    final NewOrderCommand newOrder =
        mapper.toNewOrder(newOrder(CREATED_AT.plus(1, ChronoUnit.DAYS)));
    final CancelOrderCommand cancel =
        mapper.toCancelOrder(cancel(CREATED_AT.plus(1, ChronoUnit.DAYS)));

    assertThat(newOrder.getTradingDay().getIsoDate()).isEqualTo(TRADING_DAY.toString());
    assertThat(cancel.getTradingDay().getIsoDate()).isEqualTo(TRADING_DAY.toString());
  }

  @Test
  void mapsWalTermsDirectlyToTypedV2Values() {
    final NewOrderCommand mapped = mapper.toNewOrder(newOrder(CREATED_AT));

    assertThat(mapped.getAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(mapped.getInstrument().getSymbol()).isEqualTo("2330");
    assertThat(mapped.getInstrument().getVenueMic()).isEqualTo("XTAI");
    assertThat(mapped.getSide()).isEqualTo(Side.SIDE_BUY);
    assertThat(mapped.getQuantity().getShares()).isEqualTo(10L);
    assertThat(mapped.getLimitPrice().getUnits()).isEqualTo(1_000_000L);
    assertThat(mapped.getEstimatedNotional().getUnits()).isEqualTo(10_000_000L);
    assertThat(mapped.getOrderType()).isEqualTo(OrderType.ORDER_TYPE_LIMIT);
    assertThat(mapped.getTif()).isEqualTo(TimeInForce.TIME_IN_FORCE_ROD);
    assertThat(mapped.getMetadata().getSchemaVersion()).isEqualTo("v2");
  }

  private WalRecord newOrder(Instant createdAt) {
    return new WalRecord(
        metadata(createdAt),
        session(),
        new WalOrderReference("O-C1", "C1", "", ACCOUNT_ID),
        new WalCommand.NewOrder(
            new WalOrderTerms(
                "2330",
                Side.SIDE_BUY,
                "10",
                "100",
                OrderType.ORDER_TYPE_LIMIT,
                TimeInForce.TIME_IN_FORCE_ROD)),
        new RawFixMessage("35=D"));
  }

  private WalRecord cancel(Instant createdAt) {
    return new WalRecord(
        metadata(createdAt),
        session(),
        new WalOrderReference("O-C1", "CXL-1", "C1", ACCOUNT_ID),
        new WalCommand.Cancel("2330", Side.SIDE_BUY),
        new RawFixMessage("35=F"));
  }

  private WalMetadata metadata(Instant createdAt) {
    return new WalMetadata(
        WalMetadata.CURRENT_SCHEMA_VERSION,
        UUID.randomUUID().toString(),
        createdAt.toEpochMilli(),
        "quickfix-gateway");
  }

  private FixSessionIdentity session() {
    return new FixSessionIdentity("CLIENT1", "SIMPLEMATCH");
  }
}

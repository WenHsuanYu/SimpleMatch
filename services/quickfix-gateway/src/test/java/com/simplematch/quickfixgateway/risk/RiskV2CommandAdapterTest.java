package com.simplematch.quickfixgateway.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.v2.VenueMic;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskV2CommandAdapterTest {
  private static final Instant CREATED_AT = Instant.parse("2024-03-27T08:09:10.123Z");
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

  private final RiskV2CommandAdapter adapter = new RiskV2CommandAdapter(VenueMic.parse("XTAI"));

  @Test
  void newAndCancelShareStableInternalOrderIdentity() {
    final OrderCommand newOrder = newOrder(CREATED_AT);
    final OrderCommand cancel = cancel(CREATED_AT);

    final String newRiskOrderId = adapter.toNewOrder(newOrder).getOrderId();
    final String cancelRiskOrderId = adapter.toCancelOrder(cancel).getOrderId();

    assertThat(cancelRiskOrderId).isEqualTo(newRiskOrderId);
    assertThat(UUID.fromString(newRiskOrderId)).isNotNull();
    assertThat(newOrder.getOrderId()).isEqualTo("O-C1");
    assertThat(cancel.getOrderId()).isEqualTo("O-C1");
  }

  @Test
  void tradingDaySeparatesReusedClientOrderIds() {
    final String firstDay = adapter.toNewOrder(newOrder(CREATED_AT)).getOrderId();
    final String nextDay =
        adapter.toNewOrder(newOrder(CREATED_AT.plus(1, ChronoUnit.DAYS))).getOrderId();

    assertThat(nextDay).isNotEqualTo(firstDay);
  }

  private OrderCommand newOrder(Instant createdAt) {
    return baseCommand(createdAt)
        .setCommandId(UUID.randomUUID().toString())
        .setClOrdId("C1")
        .setCommandType(CommandType.COMMAND_TYPE_NEW)
        .setQuantity("10")
        .setPrice("100")
        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
        .setTif(TimeInForce.TIME_IN_FORCE_ROD)
        .build();
  }

  private OrderCommand cancel(Instant createdAt) {
    return baseCommand(createdAt)
        .setCommandId(UUID.randomUUID().toString())
        .setClOrdId("CXL-1")
        .setOrigClOrdId("C1")
        .setCommandType(CommandType.COMMAND_TYPE_CANCEL)
        .build();
  }

  private OrderCommand.Builder baseCommand(Instant createdAt) {
    return OrderCommand.newBuilder()
        .setMetadata(
            EventMetadata.newBuilder()
                .setSchemaVersion("v1")
                .setEventId(UUID.randomUUID().toString())
                .setCreatedAtUnixMs(createdAt.toEpochMilli())
                .setSourceService("quickfix-gateway")
                .build())
        .setOrderId("O-C1")
        .setAccountId(ACCOUNT_ID)
        .setSenderCompId("CLIENT1")
        .setTargetCompId("SIMPLEMATCH")
        .setSymbol("2330")
        .setSide(Side.SIDE_BUY);
  }
}

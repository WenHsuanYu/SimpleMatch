package com.simplematch.contracts.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V1OrderCommandAdapterTest {
  private final V1OrderCommandAdapter adapter = new V1OrderCommandAdapter(VenueMic.XTAI);

  @DisplayName("a representable v1 new order round trips through the v2 ingress adapter")
  @Test
  void roundTripsNewOrder() {
    final OrderCommand v1 = newOrder();

    assertEquals(v1, adapter.toV1(adapter.toNewOrder(v1)));
  }

  @DisplayName("a representable v1 cancel order round trips through the v2 ingress adapter")
  @Test
  void roundTripsCancelOrder() {
    final OrderCommand v1 = newOrder().toBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_CANCEL)
        .setOrigClOrdId("CLIENT-ORIGINAL")
        .setQuantity("0")
        .setPrice("")
        .setOrderType(OrderType.ORDER_TYPE_UNSPECIFIED)
        .setTif(TimeInForce.TIME_IN_FORCE_UNSPECIFIED)
        .build();

    assertEquals(v1, adapter.toV1(adapter.toCancelOrder(v1)));
  }

  @DisplayName("v1 ingress rejects command identifiers that cannot become v2 UUID identifiers")
  @Test
  void rejectsNonUuidCommandId() {
    assertThrows(DomainValidationException.class, () -> adapter.toNewOrder(newOrder().toBuilder()
        .setCommandId("legacy-command")
        .build()));
  }

  private static OrderCommand newOrder() {
    return OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId(uuid())
            .setCreatedAtUnixMs(1_785_490_400_000L)
            .setSourceService("quickfix-gateway")
            .build())
        .setCommandId(uuid())
        .setOrderId(uuid())
        .setAccountId(uuid())
        .setSenderCompId("CLIENT")
        .setTargetCompId("SIMPLEMATCH")
        .setClOrdId("CLIENT-1")
        .setSymbol("2330")
        .setSide(Side.SIDE_BUY)
        .setQuantity("1000")
        .setPrice("123.4567")
        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
        .setTif(TimeInForce.TIME_IN_FORCE_ROD)
        .setCommandType(CommandType.COMMAND_TYPE_NEW)
        .build();
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }
}

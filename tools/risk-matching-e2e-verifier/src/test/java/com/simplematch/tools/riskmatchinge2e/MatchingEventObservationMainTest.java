package com.simplematch.tools.riskmatchinge2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.contracts.matching.runtime.v1.OrderTerminal;
import com.simplematch.contracts.matching.runtime.v1.TradeExecuted;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import org.junit.jupiter.api.Test;

class MatchingEventObservationMainTest {
  @Test
  void matchesOwningOrderAcrossFinalEventShapes() {
    final String orderId = "0198a000-0000-7000-8000-000000000001";

    final MatchingEvent rested =
        MatchingEvent.newBuilder()
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
            .setOrderRested(OrderRested.newBuilder().setOrderId(orderId))
            .build();
    final MatchingEvent trade =
        MatchingEvent.newBuilder()
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_TRADE_EXECUTED)
            .setTradeExecuted(
                TradeExecuted.newBuilder().setTaker(TradeLeg.newBuilder().setOrderId(orderId)))
            .build();
    final MatchingEvent cancelled =
        MatchingEvent.newBuilder()
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_CANCELLED)
            .setOrderCancelled(OrderTerminal.newBuilder().setOrderId(orderId))
            .build();
    final MatchingEvent expired =
        MatchingEvent.newBuilder()
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_EXPIRED)
            .setOrderExpired(OrderTerminal.newBuilder().setOrderId(orderId))
            .build();

    assertThat(MatchingEventObservationMain.matchesOrder(rested, orderId)).isTrue();
    assertThat(MatchingEventObservationMain.matchesOrder(trade, orderId)).isTrue();
    assertThat(MatchingEventObservationMain.matchesOrder(cancelled, orderId)).isTrue();
    assertThat(MatchingEventObservationMain.matchesOrder(expired, orderId)).isTrue();
    assertThat(
            MatchingEventObservationMain.matchesOrder(
                rested, "0198a000-0000-7000-8000-000000000002"))
        .isFalse();
  }
}

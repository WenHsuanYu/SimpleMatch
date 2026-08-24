package com.simplematch.quickfixgateway.matching;

import com.simplematch.contracts.DeterministicTextIdentity;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.contracts.matching.runtime.v1.OrderTerminal;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryIdentity;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryIntent;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryRecipient;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryReport;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.fix.OrderSessionState;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Maps final Matching Event facts to all required durable client-report intents. */
public final class FinalMatchingEventFixDeliveryPlanner {
  private static final String DELIVERY_NAMESPACE = "simplematch.fix-delivery-v1";
  private static final String LIFECYCLE_EXEC_NAMESPACE = "simplematch.fix-lifecycle-exec-v1";

  private final OrderSessionRegistry orderSessionRegistry;

  /** Creates a planner that resolves each final-event order to its originating FIX session. */
  public FinalMatchingEventFixDeliveryPlanner(OrderSessionRegistry orderSessionRegistry) {
    this.orderSessionRegistry = orderSessionRegistry;
  }

  /** Plans every client-visible report required by one validated final Matching Event. */
  public List<FinalFixDeliveryIntent> plan(
      FinalMatchingEventEnvelope envelope,
      int kafkaPartition,
      long kafkaOffset,
      long createdAtUnixMs) {
    final MatchingEvent event = envelope.event();
    return switch (event.getEventType()) {
      case MATCHING_EVENT_TYPE_ORDER_RESTED ->
          List.of(rested(envelope, kafkaPartition, kafkaOffset, createdAtUnixMs));
      case MATCHING_EVENT_TYPE_TRADE_EXECUTED -> {
        final String tradeId =
            HexFormat.of().formatHex(event.getTradeExecuted().getTradeId().toByteArray());
        yield List.of(
            tradeLeg(
                envelope,
                event.getTradeExecuted().getMaker(),
                tradeId,
                "maker",
                0,
                kafkaPartition,
                kafkaOffset,
                createdAtUnixMs),
            tradeLeg(
                envelope,
                event.getTradeExecuted().getTaker(),
                tradeId,
                "taker",
                1,
                kafkaPartition,
                kafkaOffset,
                createdAtUnixMs));
      }
      case MATCHING_EVENT_TYPE_ORDER_CANCELLED ->
          List.of(
              terminal(
                  envelope,
                  event.getOrderCancelled(),
                  '4',
                  '4',
                  kafkaPartition,
                  kafkaOffset,
                  createdAtUnixMs));
      case MATCHING_EVENT_TYPE_ORDER_EXPIRED ->
          List.of(
              terminal(
                  envelope,
                  event.getOrderExpired(),
                  'C',
                  'C',
                  kafkaPartition,
                  kafkaOffset,
                  createdAtUnixMs));
      case MATCHING_EVENT_TYPE_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("final Matching Event type is required");
    };
  }

  private FinalFixDeliveryIntent rested(
      FinalMatchingEventEnvelope envelope,
      int kafkaPartition,
      long kafkaOffset,
      long createdAtUnixMs) {
    final OrderRested rested = envelope.event().getOrderRested();
    final OrderSessionState state =
        stateFor(
            rested.getOrderId(),
            rested.getAccountId(),
            rested.getInstrument().getSymbol(),
            rested.getSide());
    final long orderQuantity = quantity(state);
    final long cumulative = cumulative(orderQuantity, rested.getLeavesQuantityShares());
    return intent(
        envelope,
        state,
        rested.getOrderId(),
        0,
        new FinalFixDeliveryReport(
            lifecycleExecutionId(envelope, rested.getOrderId()),
            '0',
            '0',
            0,
            0,
            cumulative,
            rested.getLeavesQuantityShares(),
            0,
            ""),
        kafkaPartition,
        kafkaOffset,
        createdAtUnixMs);
  }

  private FinalFixDeliveryIntent tradeLeg(
      FinalMatchingEventEnvelope envelope,
      TradeLeg leg,
      String tradeId,
      String role,
      int deliveryIndex,
      int kafkaPartition,
      long kafkaOffset,
      long createdAtUnixMs) {
    final MatchingEvent event = envelope.event();
    final OrderSessionState state =
        stateFor(
            leg.getOrderId(),
            leg.getAccountId(),
            event.getTradeExecuted().getInstrument().getSymbol(),
            leg.getSide());
    final long orderQuantity = quantity(state);
    requireLifecycleQuantities(
        orderQuantity, leg.getCumulativeQuantityShares(), leg.getLeavesQuantityShares());
    final char status =
        switch (leg.getResultingState()) {
          case TRADE_LEG_STATE_FILLED -> '2';
          case TRADE_LEG_STATE_PARTIALLY_FILLED -> '1';
          case TRADE_LEG_STATE_UNSPECIFIED, UNRECOGNIZED ->
              throw new IllegalArgumentException("validated trade leg state is required");
        };
    return intent(
        envelope,
        state,
        leg.getOrderId(),
        deliveryIndex,
        new FinalFixDeliveryReport(
            tradeId + "-" + role,
            status,
            status,
            event.getTradeExecuted().getQuantityShares(),
            event.getTradeExecuted().getPriceUnits(),
            leg.getCumulativeQuantityShares(),
            leg.getLeavesQuantityShares(),
            leg.getAveragePriceUnits(),
            ""),
        kafkaPartition,
        kafkaOffset,
        createdAtUnixMs);
  }

  private FinalFixDeliveryIntent terminal(
      FinalMatchingEventEnvelope envelope,
      OrderTerminal terminal,
      char executionType,
      char orderStatus,
      int kafkaPartition,
      long kafkaOffset,
      long createdAtUnixMs) {
    final OrderSessionState state =
        stateFor(
            terminal.getOrderId(),
            terminal.getAccountId(),
            terminal.getInstrument().getSymbol(),
            terminal.getSide());
    final long orderQuantity = quantity(state);
    final long cumulative = cumulative(orderQuantity, terminal.getLeavesQuantityShares());
    return intent(
        envelope,
        state,
        terminal.getOrderId(),
        0,
        new FinalFixDeliveryReport(
            lifecycleExecutionId(envelope, terminal.getOrderId()),
            executionType,
            orderStatus,
            0,
            0,
            cumulative,
            terminal.getLeavesQuantityShares(),
            0,
            terminal.getReason().name()),
        kafkaPartition,
        kafkaOffset,
        createdAtUnixMs);
  }

  private FinalFixDeliveryIntent intent(
      FinalMatchingEventEnvelope envelope,
      OrderSessionState state,
      String orderId,
      int deliveryIndex,
      FinalFixDeliveryReport report,
      int kafkaPartition,
      long kafkaOffset,
      long createdAtUnixMs) {
    final String eventId = envelope.eventIdHex();
    final String deliveryId =
        HexFormat.of()
            .formatHex(DeterministicTextIdentity.sha256(DELIVERY_NAMESPACE, eventId, orderId));
    return new FinalFixDeliveryIntent(
        new FinalFixDeliveryIdentity(deliveryId, eventId, deliveryIndex),
        new FinalFixDeliveryRecipient(UUID.fromString(orderId), state.sessionId(), state.order()),
        report,
        kafkaPartition,
        kafkaOffset,
        createdAtUnixMs);
  }

  private OrderSessionState stateFor(
      String orderId,
      String accountId,
      String symbol,
      com.simplematch.contracts.common.v2.Side side) {
    final OrderSessionState state =
        orderSessionRegistry
            .find(orderId)
            .orElseThrow(
                () -> new IllegalStateException("final Matching Event has no owning FIX session"));
    if (!state.accountId().equals(accountId)
        || !state.symbol().equals(symbol)
        || state.side() != side) {
      throw new IllegalArgumentException(
          "final Matching Event does not match the owning FIX order");
    }
    return state;
  }

  private long quantity(OrderSessionState state) {
    try {
      return new BigDecimal(state.quantity()).longValueExact();
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw new IllegalStateException(
          "owning FIX order quantity is not an exact share quantity", invalid);
    }
  }

  private long cumulative(long orderQuantity, long leavesQuantity) {
    if (leavesQuantity < 0 || leavesQuantity > orderQuantity) {
      throw new IllegalArgumentException(
          "final Matching Event leaves quantity exceeds the FIX order");
    }
    return orderQuantity - leavesQuantity;
  }

  private void requireLifecycleQuantities(long orderQuantity, long cumulative, long leaves) {
    if (cumulative < 0
        || leaves < 0
        || cumulative > orderQuantity
        || cumulative + leaves != orderQuantity) {
      throw new IllegalArgumentException(
          "final Matching Event quantities do not match the FIX order");
    }
  }

  private String lifecycleExecutionId(FinalMatchingEventEnvelope envelope, String orderId) {
    return HexFormat.of()
        .formatHex(
            DeterministicTextIdentity.sha256(
                LIFECYCLE_EXEC_NAMESPACE,
                envelope.eventIdHex(),
                orderId,
                envelope.event().getEventType().name()));
  }
}

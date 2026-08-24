package com.simplematch.accountservice.matching;

import com.simplematch.accountservice.authority.AccountId;
import com.simplematch.accountservice.reservation.ExecutionFill;
import com.simplematch.accountservice.reservation.MatchingAccountEffect;
import com.simplematch.accountservice.reservation.ReleaseReservationOperation;
import com.simplematch.accountservice.reservation.ReservationIdentity;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.contracts.DeterministicTextIdentity;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.OrderTerminal;
import com.simplematch.contracts.matching.runtime.v1.TradeExecuted;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import com.simplematch.contracts.matching.runtime.v1.TradeLegState;
import java.math.BigDecimal;
import java.util.List;

/** Adapts one validated final Matching Event into Account-owned application values. */
final class FinalMatchingEventAccountAdapter {
  private static final int TWD_PRICE_SCALE = 4;
  private static final String FILL_ID_NAMESPACE = "simplematch.account-final-fill-v1";
  private static final String TERMINAL_ID_NAMESPACE =
      "simplematch.account-final-terminal-v1";

  private FinalMatchingEventAccountAdapter() {}

  /** Returns the complete ordered Account command represented by one final Matching Event. */
  static FinalMatchingEventAccountCommand adapt(FinalMatchingEventEnvelope envelope) {
    final MatchingEvent event = envelope.event();
    final List<MatchingAccountEffect> effects =
        switch (event.getEventType()) {
          case MATCHING_EVENT_TYPE_ORDER_RESTED -> List.of();
          case MATCHING_EVENT_TYPE_TRADE_EXECUTED ->
              tradeEffects(envelope, event.getTradeExecuted());
          case MATCHING_EVENT_TYPE_ORDER_CANCELLED ->
              List.of(
                  terminalEffect(
                      envelope,
                      event.getOrderCancelled(),
                      "MATCHING_CANCELLED"));
          case MATCHING_EVENT_TYPE_ORDER_EXPIRED ->
              List.of(
                  terminalEffect(
                      envelope,
                      event.getOrderExpired(),
                      "MATCHING_EXPIRED"));
          default -> throw new IllegalArgumentException("final Matching Event type is required");
        };
    return new FinalMatchingEventAccountCommand(
        new FinalMatchingEventAccountCommand.EventId(envelope.eventIdBytes()),
        new FinalMatchingEventAccountCommand.PayloadFingerprint(envelope.payloadSha256()),
        effects);
  }

  private static List<MatchingAccountEffect> tradeEffects(
      FinalMatchingEventEnvelope envelope, TradeExecuted trade) {
    return List.of(
        fillEffect(envelope, trade, trade.getMaker(), "maker"),
        fillEffect(envelope, trade, trade.getTaker(), "taker"));
  }

  private static MatchingAccountEffect.Fill fillEffect(
      FinalMatchingEventEnvelope envelope,
      TradeExecuted trade,
      TradeLeg leg,
      String role) {
    final String executionId =
        DeterministicTextIdentity.uuid(
                FILL_ID_NAMESPACE, envelope.eventIdHex(), leg.getOrderId(), role)
            .toString();
    return new MatchingAccountEffect.Fill(
        new ExecutionFill.ExecutionId(executionId),
        new ReservationIdentity.OrderId(leg.getOrderId()),
        AccountId.parse(leg.getAccountId()),
        new ReservationTerms.InstrumentSymbol(trade.getInstrument().getSymbol()),
        new ExecutionFill.FillQuantity(BigDecimal.valueOf(trade.getQuantityShares())),
        new ExecutionFill.FillPrice(
            BigDecimal.valueOf(trade.getPriceUnits(), TWD_PRICE_SCALE)),
        resultingState(leg.getResultingState()));
  }

  private static MatchingAccountEffect.Terminal terminalEffect(
      FinalMatchingEventEnvelope envelope, OrderTerminal terminal, String reason) {
    final String executionId =
        DeterministicTextIdentity.uuid(
                TERMINAL_ID_NAMESPACE, envelope.eventIdHex(), terminal.getOrderId())
            .toString();
    return new MatchingAccountEffect.Terminal(
        new ExecutionFill.ExecutionId(executionId),
        new ReservationIdentity.OrderId(terminal.getOrderId()),
        AccountId.parse(terminal.getAccountId()),
        new ReservationTerms.InstrumentSymbol(terminal.getInstrument().getSymbol()),
        new ReleaseReservationOperation.ReleaseReason(reason));
  }

  private static MatchingAccountEffect.ResultingState resultingState(TradeLegState state) {
    return switch (state) {
      case TRADE_LEG_STATE_PARTIALLY_FILLED ->
          MatchingAccountEffect.ResultingState.PARTIALLY_FILLED;
      case TRADE_LEG_STATE_FILLED -> MatchingAccountEffect.ResultingState.FILLED;
      default -> throw new IllegalArgumentException("trade leg resulting state is required");
    };
  }
}

package com.simplematch.contracts.matching.runtime.v1;

/** Holds event-payload validation rules for the supported final Matching Event types. */
final class FinalMatchingEventValidationFields {
  private FinalMatchingEventValidationFields() {}

  static void validateRested(OrderRested rested) {
    FinalMatchingEventValidationRules.validateParticipant(
        rested.getOrderId(), rested.getAccountId());
    FinalMatchingEventValidationRules.validateInstrument(
        rested.getInstrument().getVenueMic(), rested.getInstrument().getSymbol());
    FinalMatchingEventValidationRules.requireKnownSide(rested.getSide());
    FinalMatchingEventValidationRules.require(
        rested.getLeavesQuantityShares() > 0, "rested leaves quantity must be positive");
    FinalMatchingEventValidationRules.require(
        rested.getRestingPriceUnits() > 0, "rested price must be positive");
  }

  static void validateTrade(TradeExecuted trade) {
    FinalMatchingEventValidationRules.requireSha256(trade.getTradeId(), "tradeId");
    FinalMatchingEventValidationRules.validateInstrument(
        trade.getInstrument().getVenueMic(), trade.getInstrument().getSymbol());
    FinalMatchingEventValidationRules.requireKnownSide(trade.getAggressorSide());
    FinalMatchingEventValidationRules.require(
        trade.getQuantityShares() > 0, "trade quantity must be positive");
    FinalMatchingEventValidationRules.require(
        trade.getPriceUnits() > 0, "trade price must be positive");
    validateLeg(trade.getMaker(), "maker", trade.getQuantityShares());
    validateLeg(trade.getTaker(), "taker", trade.getQuantityShares());
    FinalMatchingEventValidationRules.require(
        !trade.getMaker().getOrderId().equals(trade.getTaker().getOrderId()),
        "maker and taker order identities must differ");
    FinalMatchingEventValidationRules.require(
        trade.getMaker().getSide() != trade.getTaker().getSide(),
        "maker and taker sides must differ");
    FinalMatchingEventValidationRules.require(
        trade.getAggressorSide() == trade.getTaker().getSide(),
        "aggressor side must equal taker side");
  }

  static void validateTerminal(OrderTerminal terminal) {
    FinalMatchingEventValidationRules.validateParticipant(
        terminal.getOrderId(), terminal.getAccountId());
    FinalMatchingEventValidationRules.validateInstrument(
        terminal.getInstrument().getVenueMic(), terminal.getInstrument().getSymbol());
    FinalMatchingEventValidationRules.requireKnownSide(terminal.getSide());
    FinalMatchingEventValidationRules.require(
        terminal.getLeavesQuantityShares() > 0, "terminal leaves quantity must be positive");
    FinalMatchingEventValidationRules.require(
        terminal.getReason() != CancellationReason.CANCELLATION_REASON_UNSPECIFIED,
        "terminal cancellation reason is required");
  }

  private static void validateLeg(TradeLeg leg, String role, long tradeQuantity) {
    FinalMatchingEventValidationRules.validateParticipant(leg.getOrderId(), leg.getAccountId());
    FinalMatchingEventValidationRules.requireKnownSide(leg.getSide());
    FinalMatchingEventValidationRules.require(
        leg.getCumulativeQuantityShares() >= tradeQuantity,
        role + " cumulative quantity must include this trade");
    FinalMatchingEventValidationRules.require(
        leg.getLeavesQuantityShares() >= 0,
        role + " leaves quantity must not be negative");
    FinalMatchingEventValidationRules.require(
        leg.getAveragePriceUnits() > 0, role + " average price must be positive");
    if (leg.getLeavesQuantityShares() == 0) {
      FinalMatchingEventValidationRules.require(
          leg.getResultingState() == TradeLegState.TRADE_LEG_STATE_FILLED,
          role + " state must be FILLED when no quantity remains");
    } else {
      FinalMatchingEventValidationRules.require(
          leg.getResultingState() == TradeLegState.TRADE_LEG_STATE_PARTIALLY_FILLED,
          role + " state must be PARTIALLY_FILLED when quantity remains");
    }
  }
}

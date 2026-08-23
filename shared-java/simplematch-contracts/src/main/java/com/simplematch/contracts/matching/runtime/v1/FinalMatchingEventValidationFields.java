package com.simplematch.contracts.matching.runtime.v1;

import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.Side;
import java.util.UUID;

/** Holds field-level final-event validation rules shared by each supported event payload. */
final class FinalMatchingEventValidationFields {
  private FinalMatchingEventValidationFields() {}

  static void validateRested(OrderRested rested) {
    validateOrderIdentity(
        rested.getOrderId(),
        rested.getAccountId(),
        rested.getInstrument().getVenueMic(),
        rested.getInstrument().getSymbol());
    requireKnownSide(rested.getSide());
    require(rested.getLeavesQuantityShares() > 0, "rested leaves quantity must be positive");
    require(rested.getRestingPriceUnits() > 0, "rested price must be positive");
  }

  static void validateTrade(TradeExecuted trade) {
    requireSha256(trade.getTradeId(), "tradeId");
    require(!trade.getInstrument().getVenueMic().isBlank(), "trade venue must not be blank");
    require(!trade.getInstrument().getSymbol().isBlank(), "trade symbol must not be blank");
    requireKnownSide(trade.getAggressorSide());
    require(trade.getQuantityShares() > 0, "trade quantity must be positive");
    require(trade.getPriceUnits() > 0, "trade price must be positive");
    validateLeg(trade.getMaker(), "maker", trade.getQuantityShares());
    validateLeg(trade.getTaker(), "taker", trade.getQuantityShares());
    require(
        !trade.getMaker().getOrderId().equals(trade.getTaker().getOrderId()),
        "maker and taker order identities must differ");
    require(
        trade.getMaker().getSide() != trade.getTaker().getSide(),
        "maker and taker sides must differ");
    require(
        trade.getAggressorSide() == trade.getTaker().getSide(),
        "aggressor side must equal taker side");
  }

  static void validateTerminal(OrderTerminal terminal) {
    validateOrderIdentity(
        terminal.getOrderId(),
        terminal.getAccountId(),
        terminal.getInstrument().getVenueMic(),
        terminal.getInstrument().getSymbol());
    requireKnownSide(terminal.getSide());
    require(terminal.getLeavesQuantityShares() > 0, "terminal leaves quantity must be positive");
    require(
        terminal.getReason() != CancellationReason.CANCELLATION_REASON_UNSPECIFIED,
        "terminal cancellation reason is required");
  }

  static void requireSha256(ByteString value, String name) {
    require(value != null && value.size() == 32, name + " must contain exactly 32 bytes");
  }

  static void requireCanonicalHex(String value, String name) {
    require(
        value != null && value.matches("[0-9a-f]{64}"), name + " must be lowercase SHA-256 hex");
  }

  static void requireUuid(String value, String name) {
    try {
      UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(name + " must be a UUID", invalid);
    }
  }

  static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void validateLeg(TradeLeg leg, String role, long tradeQuantity) {
    validateOrderIdentity(leg.getOrderId(), leg.getAccountId(), "X", "X");
    requireKnownSide(leg.getSide());
    require(
        leg.getCumulativeQuantityShares() >= tradeQuantity,
        role + " cumulative quantity must include this trade");
    require(leg.getLeavesQuantityShares() >= 0, role + " leaves quantity must not be negative");
    require(leg.getAveragePriceUnits() > 0, role + " average price must be positive");
    if (leg.getLeavesQuantityShares() == 0) {
      require(
          leg.getResultingState() == TradeLegState.TRADE_LEG_STATE_FILLED,
          role + " state must be FILLED when no quantity remains");
    } else {
      require(
          leg.getResultingState() == TradeLegState.TRADE_LEG_STATE_PARTIALLY_FILLED,
          role + " state must be PARTIALLY_FILLED when quantity remains");
    }
  }

  private static void validateOrderIdentity(
      String orderId, String accountId, String venueMic, String symbol) {
    requireUuid(orderId, "orderId");
    requireUuid(accountId, "accountId");
    require(!venueMic.isBlank(), "venueMic must not be blank");
    require(!symbol.isBlank(), "symbol must not be blank");
  }

  private static void requireKnownSide(Side side) {
    require(side == Side.SIDE_BUY || side == Side.SIDE_SELL, "side must be BUY or SELL");
  }
}

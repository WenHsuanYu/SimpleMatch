package com.simplematch.contracts.matching.runtime.v1;

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
    requireCanonicalHex(trade.getTradeId(), "tradeId");
    require(!trade.getInstrument().getVenueMic().isBlank(), "trade venue must not be blank");
    require(!trade.getInstrument().getSymbol().isBlank(), "trade symbol must not be blank");
    validateLeg(trade.getMaker(), "maker");
    validateLeg(trade.getTaker(), "taker");
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

  private static void validateLeg(TradeLeg leg, String role) {
    validateOrderIdentity(leg.getOrderId(), leg.getAccountId(), "X", "X");
    requireKnownSide(leg.getSide());
    require(leg.getQuantityShares() > 0, role + " quantity must be positive");
    require(leg.getPriceUnits() > 0, role + " price must be positive");
    require(
        leg.getCumulativeQuantityShares() >= leg.getQuantityShares(),
        role + " cumulative quantity must include this fill");
    require(leg.getLeavesQuantityShares() >= 0, role + " leaves quantity must not be negative");
    require(leg.getAveragePriceUnits() >= 0, role + " average price must not be negative");
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

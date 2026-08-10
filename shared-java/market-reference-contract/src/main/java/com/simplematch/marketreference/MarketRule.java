package com.simplematch.marketreference;

/** Reusable lot and tick-table facts for a class of market instruments. */
public record MarketRule(String ruleId, int boardLotShares, String tickTableId) {
  /** Validates the reusable rule reference. */
  public MarketRule {
    if (ruleId == null || ruleId.isBlank()) {
      throw new MarketReferenceValidationException("market rule id is required");
    }
    if (boardLotShares <= 0) {
      throw new MarketReferenceValidationException("market-rule board lot must be positive");
    }
    if (tickTableId == null || tickTableId.isBlank()) {
      throw new MarketReferenceValidationException("market-rule tick table id is required");
    }
  }
}

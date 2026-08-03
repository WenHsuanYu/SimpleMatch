package com.simplematch.marketdatapublisher.snapshot;

/** Immutable daily reference data and phase-one eligibility for one venue instrument. */
public record MarketInstrument(
    InstrumentIdentity identity,
    InstrumentTradingRules tradingRules,
    EligibilityReason eligibilityReason) {
  /** Validates fully normalized instrument data. */
  public MarketInstrument {
    java.util.Objects.requireNonNull(identity, "instrument identity is required");
    java.util.Objects.requireNonNull(tradingRules, "instrument trading rules are required");
    java.util.Objects.requireNonNull(eligibilityReason, "eligibility reason is required");
  }

  /** Returns whether phase-one order admission may trade this instrument. */
  public boolean eligible() {
    return eligibilityReason == EligibilityReason.ELIGIBLE;
  }

  /** Returns the source symbol for callers that do not need the complete identity. */
  public String symbol() {
    return identity.symbol();
  }

  /** Returns the normalized venue MIC for callers that do not need the complete identity. */
  public String venueMic() {
    return identity.venueMic();
  }

  /** Returns the board-lot size from the instrument trading rules. */
  public int boardLotShares() {
    return tradingRules.boardLotShares();
  }

  /** Returns the validated tick table from the instrument trading rules. */
  public TickTable tickTable() {
    return tradingRules.tickTable();
  }

  /** Returns the reference price in fixed-point units. */
  public long referencePriceUnits() {
    return tradingRules.referencePriceBand().referencePriceUnits();
  }

  /** Returns the lower price limit in fixed-point units. */
  public long lowerPriceLimitUnits() {
    return tradingRules.referencePriceBand().lowerPriceLimitUnits();
  }

  /** Returns the upper price limit in fixed-point units. */
  public long upperPriceLimitUnits() {
    return tradingRules.referencePriceBand().upperPriceLimitUnits();
  }
}

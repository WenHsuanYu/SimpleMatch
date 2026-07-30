package com.simplematch.marketdatapublisher.snapshot;

import java.util.Objects;

/** Immutable daily reference data and phase-one eligibility for one venue instrument. */
public record MarketInstrument(
    String symbol,
    String venueMic,
    int boardLotShares,
    TickTable tickTable,
    long referencePriceUnits,
    long lowerPriceLimitUnits,
    long upperPriceLimitUnits,
    EligibilityReason eligibilityReason) {
  /** Validates fully normalized instrument data. */
  public MarketInstrument {
    if (symbol == null || symbol.isBlank()) {
      throw new MarketSnapshotValidationException("instrument symbol is required");
    }
    if (venueMic == null || venueMic.isBlank()) {
      throw new MarketSnapshotValidationException("instrument venue is required");
    }
    if (boardLotShares <= 0) {
      throw new MarketSnapshotValidationException("board lot must be positive");
    }
    Objects.requireNonNull(tickTable, "tick table is required");
    if (referencePriceUnits <= 0 || lowerPriceLimitUnits <= 0 || upperPriceLimitUnits <= 0) {
      throw new MarketSnapshotValidationException(
          "reference price and price limits must be positive");
    }
    if (lowerPriceLimitUnits >= referencePriceUnits
        || upperPriceLimitUnits <= referencePriceUnits) {
      throw new MarketSnapshotValidationException("price limits must bracket the reference price");
    }
    if (!tickTable.accepts(referencePriceUnits)
        || !tickTable.accepts(lowerPriceLimitUnits)
        || !tickTable.accepts(upperPriceLimitUnits)) {
      throw new MarketSnapshotValidationException(
          "reference price and price limits must align to the tick table");
    }
    Objects.requireNonNull(eligibilityReason, "eligibility reason is required");
  }

  /** Returns whether phase-one order admission may trade this instrument. */
  public boolean eligible() {
    return eligibilityReason == EligibilityReason.ELIGIBLE;
  }
}

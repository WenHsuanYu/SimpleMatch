package com.simplematch.marketdatapublisher.snapshot;

/** Validated lower, reference, and upper prices for one market instrument. */
public record ReferencePriceBand(
    long referencePriceUnits, long lowerPriceLimitUnits, long upperPriceLimitUnits) {
  /** Validates positive limits that bracket the reference price. */
  public ReferencePriceBand {
    if (referencePriceUnits <= 0 || lowerPriceLimitUnits <= 0 || upperPriceLimitUnits <= 0) {
      throw new MarketSnapshotValidationException(
          "reference price and price limits must be positive");
    }
    if (lowerPriceLimitUnits >= referencePriceUnits
        || upperPriceLimitUnits <= referencePriceUnits) {
      throw new MarketSnapshotValidationException("price limits must bracket the reference price");
    }
  }
}

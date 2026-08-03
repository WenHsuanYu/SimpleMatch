package com.simplematch.marketdatapublisher.snapshot;

import java.util.Objects;

/** Board-lot and price-grid rules for one normalized market instrument. */
public record InstrumentTradingRules(
    int boardLotShares, TickTable tickTable, ReferencePriceBand referencePriceBand) {
  /** Validates the lot size and aligns every reference price to the tick table. */
  public InstrumentTradingRules {
    if (boardLotShares <= 0) {
      throw new MarketSnapshotValidationException("board lot must be positive");
    }
    Objects.requireNonNull(tickTable, "tick table is required");
    Objects.requireNonNull(referencePriceBand, "reference price band is required");
    if (!tickTable.accepts(referencePriceBand.referencePriceUnits())
        || !tickTable.accepts(referencePriceBand.lowerPriceLimitUnits())
        || !tickTable.accepts(referencePriceBand.upperPriceLimitUnits())) {
      throw new MarketSnapshotValidationException(
          "reference price and price limits must align to the tick table");
    }
  }
}

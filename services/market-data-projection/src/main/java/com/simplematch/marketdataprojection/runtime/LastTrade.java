package com.simplematch.marketdataprojection.runtime;

/** The most recent execution for one instrument in the runtime market-data projection. */
public record LastTrade(long priceUnits, long quantityShares) {
  /** Validates an immutable positive execution fact. */
  public LastTrade {
    if (priceUnits <= 0 || quantityShares <= 0) {
      throw new IllegalArgumentException("last trade price and quantity must be positive");
    }
  }
}

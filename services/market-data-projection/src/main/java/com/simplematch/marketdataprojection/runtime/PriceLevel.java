package com.simplematch.marketdataprojection.runtime;

/** One aggregate public order-book quantity at one positive price. */
public record PriceLevel(long priceUnits, long quantityShares) {
  /** Validates one complete visible price level. */
  public PriceLevel {
    if (priceUnits <= 0 || quantityShares <= 0) {
      throw new IllegalArgumentException("market-data price levels must be positive");
    }
  }
}

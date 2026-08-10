package com.simplematch.marketreference.builder;

/** Exact raw official reference and daily price-limit facts for one known instrument. */
record PriceFacts(long referencePriceUnits, long lowerPriceLimitUnits, long upperPriceLimitUnits) {
  /** Returns whether the source row can support trading for the target day. */
  boolean hasUsablePriceBand() {
    return lowerPriceLimitUnits < referencePriceUnits
        && referencePriceUnits < upperPriceLimitUnits;
  }
}

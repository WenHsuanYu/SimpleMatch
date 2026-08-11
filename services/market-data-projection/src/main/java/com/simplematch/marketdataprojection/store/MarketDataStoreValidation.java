package com.simplematch.marketdataprojection.store;

/** Shared validation for bounded market-data store queries. */
final class MarketDataStoreValidation {
  private MarketDataStoreValidation() {}

  static void requirePositiveLimit(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("query limit must be positive");
    }
  }
}

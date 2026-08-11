package com.simplematch.marketdataprojection.store;

/** Validated source Kafka position and observation time for one market-data projection update. */
record MarketDataProjectionPosition(int partition, long offset, long observedAtUnixMs) {
  MarketDataProjectionPosition {
    if (partition < 0 || partition > 14 || offset < 0 || observedAtUnixMs < 0) {
      throw new IllegalArgumentException("matching event Kafka position is invalid");
    }
  }
}

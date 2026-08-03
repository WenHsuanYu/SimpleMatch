package com.simplematch.marketdatapublisher.snapshot;

/** Raw source trading terms retained as text until fixed-point normalization. */
record SourceTradingTerms(
    int boardLotShares,
    String referencePrice,
    String lowerPriceLimit,
    String upperPriceLimit) {}

package com.simplematch.queryservice.model;

/** Versioned read-side execution projection. */
public record QueryExecutionView(
    String executionId,
    String orderId,
    String accountId,
    String venueMic,
    String symbol,
    String side,
    long fillQuantityShares,
    long fillPriceUnits,
    long cumulativeQuantityShares,
    long leavesQuantityShares,
    long averagePriceUnits,
    String sourceEventId,
    long executedAtUnixMs) {}

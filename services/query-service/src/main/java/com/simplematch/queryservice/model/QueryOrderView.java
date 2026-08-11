package com.simplematch.queryservice.model;

/** Versioned read-side order projection. */
public record QueryOrderView(
    String orderId,
    String accountId,
    String venueMic,
    String symbol,
    String side,
    String state,
    long leavesQuantityShares,
    String lastEventId,
    long updatedAtUnixMs) {}

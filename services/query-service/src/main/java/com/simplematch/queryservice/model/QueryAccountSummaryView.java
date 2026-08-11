package com.simplematch.queryservice.model;

/** Versioned read-side Account lifecycle summary. */
public record QueryAccountSummaryView(
    String accountId,
    String lifecycleState,
    long reservedNotionalUnits,
    long reservedQuantityShares,
    String reasonCode,
    String reasonDetail,
    String sourceEventId,
    long updatedAtUnixMs) {}

package com.simplematch.queryservice.model;

import java.time.LocalDate;

/** Versioned read-side view of one active market-reference instrument. */
public record QueryMarketReferenceView(
    LocalDate tradingDay,
    String artifactId,
    String venueMic,
    String symbol,
    String marketRuleId,
    Long referencePriceUnits,
    Long lowerPriceLimitUnits,
    Long upperPriceLimitUnits,
    Integer routingPartition,
    long updatedAtUnixMs) {}

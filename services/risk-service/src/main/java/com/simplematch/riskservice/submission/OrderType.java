package com.simplematch.riskservice.submission;

/**
 * Domain-side order type used by submission commands.
 */
public enum OrderType {
    ORDER_TYPE_UNSPECIFIED,
    ORDER_TYPE_LIMIT,
    ORDER_TYPE_MARKET
}
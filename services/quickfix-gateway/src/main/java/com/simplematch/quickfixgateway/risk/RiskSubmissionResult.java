package com.simplematch.quickfixgateway.risk;

/** Captures risk admission's accepted/rejected response for a submitted command. */
public record RiskSubmissionResult(
    String orderId, boolean accepted, String reasonCode, String reasonText) {}

package com.simplematch.riskservice.store;

public record StoredSubmission(
    String idempotencyKey,
    String requestId,
    String orderId,
    String clientOrderId,
    String originalClientOrderId,
    String commandType,
    boolean accepted,
    String reasonCode,
    String reasonText,
    long createdAtUnixMs) {
}
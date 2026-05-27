package com.simplematch.riskservice.submission;

public record SubmissionResult(
    String idempotencyKey,
    String requestId,
    String orderId,
    String clientOrderId,
    String originalClientOrderId,
    CommandType commandType,
    boolean accepted,
    String reasonCode,
    String reasonText,
    long createdAtUnixMs) {
}
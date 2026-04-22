package com.simplematch.riskservice.submission;

import com.simplematch.contracts.orders.v1.CommandType;

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
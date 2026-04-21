package com.simplematch.quickfixgateway.risk;

public record RiskSubmissionResult(String orderId, boolean accepted, String reasonCode, String reasonText) {
}
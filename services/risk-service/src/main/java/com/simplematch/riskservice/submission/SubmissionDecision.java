package com.simplematch.riskservice.submission;

import com.simplematch.contracts.orders.v1.OrderCommand;

public record SubmissionDecision(
    SubmissionResult submission,
    OrderCommand normalizedCommand) {
}
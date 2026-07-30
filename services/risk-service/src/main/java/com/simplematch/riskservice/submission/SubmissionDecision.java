package com.simplematch.riskservice.submission;

public record SubmissionDecision(SubmissionResult submission, ResolvedSubmissionCommand command) {}

package com.simplematch.riskservice.submission;

/** Couples a validated submission outcome with its normalized command. */
public record SubmissionDecision(SubmissionResult submission, ResolvedSubmissionCommand command) {}

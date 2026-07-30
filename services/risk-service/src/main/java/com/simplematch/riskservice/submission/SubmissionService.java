package com.simplematch.riskservice.submission;

/** Persists normalized risk submissions and their terminal outcomes. */
public interface SubmissionService {
  /** Persists the command or returns its prior idempotent outcome. */
  SubmissionResult persist(ResolvedSubmissionCommand command);
}

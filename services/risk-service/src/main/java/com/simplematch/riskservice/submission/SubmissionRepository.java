package com.simplematch.riskservice.submission;

import java.util.Optional;

/** Stores idempotent risk-submission outcomes. */
public interface SubmissionRepository {
  /** Finds an existing outcome by its business idempotency key. */
  Optional<SubmissionResult> findByBusinessKey(SubmissionBusinessKey businessKey);

  /** Inserts a submission outcome with its corresponding outbox event identifier. */
  void insert(SubmissionResult submission, String outboxEventId);
}

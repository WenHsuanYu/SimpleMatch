package com.simplematch.riskservice.submission;

import java.util.Optional;

public interface SubmissionRepository {
  Optional<SubmissionResult> findByIdempotencyKey(String idempotencyKey);

  void insert(SubmissionResult submission, String outboxEventId);
}
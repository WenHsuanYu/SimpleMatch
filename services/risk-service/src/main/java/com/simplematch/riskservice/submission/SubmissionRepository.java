package com.simplematch.riskservice.submission;

import java.util.Optional;

public interface SubmissionRepository {
    Optional<SubmissionResult> findByBusinessKey(SubmissionBusinessKey businessKey);

    void insert(SubmissionResult submission, String outboxEventId);
}
package com.simplematch.riskservice.submission;

public interface SubmissionService {
  SubmissionResult persist(SubmissionCommand command);
}
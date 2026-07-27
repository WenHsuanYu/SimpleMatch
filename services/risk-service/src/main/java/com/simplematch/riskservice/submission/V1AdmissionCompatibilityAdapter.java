package com.simplematch.riskservice.submission;

import java.util.Objects;

/** Keeps the legacy v1 submission entry point stable while v2 admission is introduced. */
public final class V1AdmissionCompatibilityAdapter implements SubmissionService {
  private final SubmissionService delegate;

  /** Creates a compatibility adapter around the existing v1 transaction flow. */
  public V1AdmissionCompatibilityAdapter(SubmissionService delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public SubmissionResult persist(ResolvedSubmissionCommand command) {
    return delegate.persist(command);
  }
}

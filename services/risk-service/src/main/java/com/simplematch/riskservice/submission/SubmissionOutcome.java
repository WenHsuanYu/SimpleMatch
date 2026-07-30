package com.simplematch.riskservice.submission;

import java.util.Objects;

/**
 * Accepted or rejected domain outcome of risk submission validation.
 *
 * @param accepted whether the submission was accepted
 * @param rejection the stable rejection reason, absent only for acceptance
 */
public record SubmissionOutcome(boolean accepted, SubmissionRejection rejection) {
  /** Enforces that acceptance has no rejection and rejection always has a reason. */
  public SubmissionOutcome {
    if (accepted && rejection != null) {
      throw new IllegalArgumentException("accepted submission must not carry a rejection");
    }
    if (!accepted) {
      rejection = Objects.requireNonNull(rejection, "rejection");
    }
  }

  /** Returns a successful outcome. */
  public static SubmissionOutcome acceptedOutcome() {
    return new SubmissionOutcome(true, null);
  }

  /** Returns a rejected outcome with a stable reason. */
  public static SubmissionOutcome rejectedOutcome(SubmissionRejection rejection) {
    return new SubmissionOutcome(false, rejection);
  }

  /** Returns the reason code, or blank for acceptance. */
  public String reasonCode() {
    return rejection == null ? "" : rejection.code().value();
  }

  /** Returns the reason detail, or blank for acceptance. */
  public String reasonText() {
    return rejection == null ? "" : rejection.detail().value();
  }
}

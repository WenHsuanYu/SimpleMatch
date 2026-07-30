package com.simplematch.riskservice.submission;

import java.util.Objects;

/**
 * Stable business rejection emitted when a submission cannot enter admission.
 *
 * <p>The machine-readable code and human-readable detail use different Java types, preventing a
 * caller from reversing them while constructing a rejection.
 *
 * @param code the machine-readable reason code
 * @param detail the operator- and client-readable reason detail
 */
public record SubmissionRejection(Code code, Detail detail) {
  /** Requires a complete typed rejection reason. */
  public SubmissionRejection {
    code = Objects.requireNonNull(code, "code");
    detail = Objects.requireNonNull(detail, "detail");
  }

  /** Stable machine-readable rejection code. */
  public record Code(String value) {
    /** Requires a nonblank rejection code. */
    public Code {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("rejection code must not be blank");
      }
    }
  }

  /** Human-readable rejection detail. */
  public record Detail(String value) {
    /** Requires a nonblank rejection detail. */
    public Detail {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("rejection detail must not be blank");
      }
    }
  }
}

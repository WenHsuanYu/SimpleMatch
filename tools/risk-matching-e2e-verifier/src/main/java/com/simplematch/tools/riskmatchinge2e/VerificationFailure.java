package com.simplematch.tools.riskmatchinge2e;

import java.util.Objects;

/** Carries stable stage and failure-code evidence across verifier boundaries. */
final class VerificationFailure extends RuntimeException {
  enum Stage {
    ADMISSION_SUBMISSION,
    ADMISSION_RECONCILIATION,
    KAFKA_OBSERVATION,
    KAFKA_VALIDATION
  }

  enum Code {
    ADMISSION_SUBMISSION_FAILED,
    ADMISSION_RECONCILIATION_FAILED,
    ADMISSION_NOT_FOUND,
    ADMISSION_REJECTED,
    ADMISSION_REMAINED_PENDING,
    ADMISSION_IDENTITY_MISMATCH,
    KAFKA_COMMAND_NOT_OBSERVED,
    KAFKA_COMMAND_INVALID
  }

  private final Stage stage;
  private final Code code;

  VerificationFailure(Stage stage, Code code, String message) {
    super(message);
    this.stage = Objects.requireNonNull(stage, "stage is required");
    this.code = Objects.requireNonNull(code, "code is required");
  }

  VerificationFailure(Stage stage, Code code, String message, Throwable cause) {
    super(message, cause);
    this.stage = Objects.requireNonNull(stage, "stage is required");
    this.code = Objects.requireNonNull(code, "code is required");
  }

  Stage stage() {
    return stage;
  }

  Code code() {
    return code;
  }
}

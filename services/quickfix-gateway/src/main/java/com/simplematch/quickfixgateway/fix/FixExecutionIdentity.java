package com.simplematch.quickfixgateway.fix;

import java.time.Instant;
import java.util.Objects;

/**
 * Identity and event time of one FIX execution report.
 *
 * @param executionId the FIX ExecID
 * @param transactTime the FIX TransactTime
 */
public record FixExecutionIdentity(ExecutionId executionId, Instant transactTime) {
  /** Requires a complete execution identity. */
  public FixExecutionIdentity {
    executionId = Objects.requireNonNull(executionId, "executionId");
    transactTime = Objects.requireNonNull(transactTime, "transactTime");
  }

  /** Execution identity rendered in FIX tag 17. */
  public record ExecutionId(String value) {
    /** Requires a nonblank execution identity. */
    public ExecutionId {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("execution_id must not be blank");
      }
    }
  }
}

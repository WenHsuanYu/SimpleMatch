package com.simplematch.quickfixgateway.fix;

import java.time.Instant;
import java.util.Objects;

/** Carries one wire-safe new-order preparation failure and its request timestamp. */
final class NewOrderPreparationFailure extends Exception {
  private final FixInboundValidationFailure validationFailure;
  private final Instant occurredAt;

  NewOrderPreparationFailure(
      FixInboundValidationFailure validationFailure, Instant occurredAt, Throwable cause) {
    super(validationFailure.reasonCode() + ": " + validationFailure.reasonText(), cause);
    this.validationFailure = Objects.requireNonNull(validationFailure, "validationFailure");
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
  }

  NewOrderPreparationFailure(FixInboundValidationFailure validationFailure, Instant occurredAt) {
    this(validationFailure, occurredAt, null);
  }

  /** Returns the protocol-level reason to render for the rejected message. */
  FixInboundValidationFailure validationFailure() {
    return validationFailure;
  }

  /** Returns the timestamp captured before validation and normalization began. */
  Instant occurredAt() {
    return occurredAt;
  }
}

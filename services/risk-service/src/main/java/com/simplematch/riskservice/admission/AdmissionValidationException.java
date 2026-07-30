package com.simplematch.riskservice.admission;

import java.util.Objects;

/** Rejection raised for transport-independent v2 admission validation. */
public final class AdmissionValidationException extends IllegalArgumentException {
  private final AdmissionFailure failure;

  /**
   * @deprecated Use {@link #AdmissionValidationException(AdmissionFailure)} so reason code and
   *     detail cannot be exchanged positionally.
   */
  @Deprecated(forRemoval = false)
  public AdmissionValidationException(String reasonCode, String detail) {
    this(
        new AdmissionFailure(
            new AdmissionFailure.ReasonCode(reasonCode), new AdmissionFailure.Detail(detail)));
  }

  /** Creates a validation rejection from the domain failure value. */
  public AdmissionValidationException(AdmissionFailure failure) {
    super(
        Objects.requireNonNull(failure, "failure").reasonCode().value()
            + ": "
            + failure.detail().value());
    this.failure = failure;
  }

  /** Returns the complete domain failure. */
  public AdmissionFailure failure() {
    return failure;
  }

  /** Returns the stable machine-readable reason. */
  public String reasonCode() {
    return failure.reasonCode().value();
  }

  /** Returns the human-readable validation detail. */
  public String detail() {
    return failure.detail().value();
  }
}

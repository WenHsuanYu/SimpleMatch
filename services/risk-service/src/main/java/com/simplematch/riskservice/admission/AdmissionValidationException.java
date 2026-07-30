package com.simplematch.riskservice.admission;

/** Rejection raised for transport-independent v2 admission validation. */
public final class AdmissionValidationException extends IllegalArgumentException {
  /** Creates a validation rejection with a stable reason code and detail. */
  public AdmissionValidationException(String reasonCode, String detail) {
    super(reasonCode + ": " + detail);
    this.reasonCode = reasonCode;
    this.detail = detail;
  }

  private final String reasonCode;
  private final String detail;

  /** Returns the stable machine-readable reason. */
  public String reasonCode() {
    return reasonCode;
  }

  /** Returns the human-readable validation detail. */
  public String detail() {
    return detail;
  }
}

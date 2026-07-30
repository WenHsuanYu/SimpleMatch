package com.simplematch.quickfixgateway.risk;

/** Reports a stable gateway-facing reason when risk admission cannot complete. */
public final class RiskSubmissionFailure extends RuntimeException {
  private final String reasonCode;
  private final String reasonText;

  private RiskSubmissionFailure(String reasonCode, String reasonText, Throwable cause) {
    super(reasonText, cause);
    this.reasonCode = reasonCode;
    this.reasonText = reasonText;
  }

  /** Creates the failure returned while the risk circuit breaker is open. */
  public static RiskSubmissionFailure circuitOpen() {
    return new RiskSubmissionFailure(
        "RISK_CIRCUIT_OPEN", "risk-service circuit breaker is open", null);
  }

  /** Creates the failure returned after the named risk operation exhausts its retry budget. */
  public static RiskSubmissionFailure unavailable(String operation, int attempts, Throwable cause) {
    return new RiskSubmissionFailure(
        "RISK_UNAVAILABLE",
        "risk-service " + operation + " failed after " + attempts + " attempt(s)",
        cause);
  }

  /** Creates the failure returned when retry backoff is interrupted. */
  public static RiskSubmissionFailure interrupted(Throwable cause) {
    return new RiskSubmissionFailure(
        "RISK_INTERRUPTED", "risk-service retry backoff interrupted", cause);
  }

  /** Returns the stable machine-readable failure reason. */
  public String reasonCode() {
    return reasonCode;
  }

  /** Returns the operator-facing failure explanation. */
  public String reasonText() {
    return reasonText;
  }
}

package com.simplematch.quickfixgateway.risk;

public final class RiskSubmissionFailure extends RuntimeException {
  private final String reasonCode;
  private final String reasonText;

  private RiskSubmissionFailure(String reasonCode, String reasonText, Throwable cause) {
    super(reasonText, cause);
    this.reasonCode = reasonCode;
    this.reasonText = reasonText;
  }

  public static RiskSubmissionFailure circuitOpen() {
    return new RiskSubmissionFailure(
        "RISK_CIRCUIT_OPEN",
        "risk-service circuit breaker is open",
        null);
  }

  public static RiskSubmissionFailure unavailable(String operation, int attempts, Throwable cause) {
    return new RiskSubmissionFailure(
        "RISK_UNAVAILABLE",
        "risk-service " + operation + " failed after " + attempts + " attempt(s)",
        cause);
  }

  public static RiskSubmissionFailure interrupted(Throwable cause) {
    return new RiskSubmissionFailure(
        "RISK_INTERRUPTED",
        "risk-service retry backoff interrupted",
        cause);
  }

  public String reasonCode() {
    return reasonCode;
  }

  public String reasonText() {
    return reasonText;
  }
}
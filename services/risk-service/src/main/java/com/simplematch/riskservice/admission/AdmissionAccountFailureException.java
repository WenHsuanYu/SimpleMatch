package com.simplematch.riskservice.admission;

/** Indicates an account-service failure that is not a client validation or retryable outage. */
public final class AdmissionAccountFailureException extends RuntimeException {
  /** Creates an internal account dependency failure without exposing transport details. */
  public AdmissionAccountFailureException(Throwable cause) {
    super("account reservation failed internally", cause);
  }
}

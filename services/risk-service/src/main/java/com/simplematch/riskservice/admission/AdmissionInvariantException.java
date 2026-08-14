package com.simplematch.riskservice.admission;

/** Indicates that Account rejected a reservation because its authoritative invariant failed. */
public final class AdmissionInvariantException extends RuntimeException {
  /** Creates an invariant failure while preserving the remote transport cause. */
  public AdmissionInvariantException(Throwable cause) {
    super("account reservation invariant failed", cause);
  }
}

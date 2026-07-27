package com.simplematch.riskservice.admission;

/** Stable conflict raised when a business identity is already owned by another command. */
public final class AdmissionConflictException extends RuntimeException {
  /** Creates a stable admission conflict. */
  public AdmissionConflictException() {
    super("admission business identity already belongs to another command");
  }
}

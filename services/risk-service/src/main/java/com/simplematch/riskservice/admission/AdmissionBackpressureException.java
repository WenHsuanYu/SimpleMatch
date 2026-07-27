package com.simplematch.riskservice.admission;

/** Indicates that durable publication lag has reached the admission safety bound. */
public final class AdmissionBackpressureException extends RuntimeException {
  /** Creates a stable backpressure failure. */
  public AdmissionBackpressureException(String message) {
    super(message);
  }
}

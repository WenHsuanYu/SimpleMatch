package com.simplematch.riskservice.admission;

/** Default policy for environments without a live CDC lag metric. */
public final class NoopAdmissionBackpressurePolicy implements AdmissionBackpressurePolicy {
  /** Performs no rejection. */
  @Override
  public void check() {}
}

package com.simplematch.riskservice.admission;

/** Admission policy used only when CDC delivery-lag backpressure is explicitly disabled. */
public final class NoopAdmissionBackpressurePolicy implements AdmissionBackpressurePolicy {
  /** Performs no rejection. */
  @Override
  public void check() {}
}

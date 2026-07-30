package com.simplematch.riskservice.admission;

/** Admission gate that keeps CDC and outbox backlog from exhausting local resources. */
public interface AdmissionBackpressurePolicy {
  /** Throws when the current durable publication backlog is above its safe bound. */
  void check();
}

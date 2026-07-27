package com.simplematch.riskservice.admission;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Rejects new admissions when the observed durable publication lag exceeds a bound. */
public final class CdcLagBackpressurePolicy implements AdmissionBackpressurePolicy {
  private final LongSupplier lagSupplier;
  private final long maximumLag;

  /** Creates a policy backed by a live CDC/outbox lag metric. */
  public CdcLagBackpressurePolicy(LongSupplier lagSupplier, long maximumLag) {
    this.lagSupplier = Objects.requireNonNull(lagSupplier, "lagSupplier");
    if (maximumLag < 0) {
      throw new IllegalArgumentException("maximumLag must not be negative");
    }
    this.maximumLag = maximumLag;
  }

  @Override
  public void check() {
    final long lag = lagSupplier.getAsLong();
    if (lag < 0 || lag > maximumLag) {
      throw new AdmissionBackpressureException("durable admission backlog exceeds safe bound");
    }
  }
}

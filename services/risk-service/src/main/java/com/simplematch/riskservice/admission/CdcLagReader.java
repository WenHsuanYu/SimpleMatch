package com.simplematch.riskservice.admission;

/** Reads the current durable CDC delivery-lag state. */
public interface CdcLagReader {

  /**
   * Reads the latest state for the named CDC metric.
   *
   * @param metricName stable metric identity
   * @return the latest durable lag snapshot
   * @throws AdmissionBackpressureException when the metric cannot be read or violates its
   *     persistence invariants
   */
  CdcLagSnapshot read(String metricName);
}

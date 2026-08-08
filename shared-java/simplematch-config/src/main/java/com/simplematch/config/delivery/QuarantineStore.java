package com.simplematch.config.delivery;

/** Persistence port for critical-consumer quarantine evidence. */
public interface QuarantineStore {
  /** Persists one failed record before its partition is paused. */
  void save(QuarantineEvidence evidence);

  /** Marks the exact quarantined record recovered after operator investigation. */
  void markRecovered(DeliveryPosition position, long recoveredAtUnixMs);
}

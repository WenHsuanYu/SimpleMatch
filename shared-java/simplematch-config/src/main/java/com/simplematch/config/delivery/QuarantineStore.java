package com.simplematch.config.delivery;

import java.util.List;

/** Persistence port for critical-consumer quarantine evidence. */
public interface QuarantineStore {
  /** Persists one failed record before its partition is paused. */
  void save(QuarantineEvidence evidence);

  /** Returns unresolved durable quarantine positions for one critical consumer. */
  default List<DeliveryPosition> loadOpenPositions(String consumerName) {
    return List.of();
  }

  /** Marks the exact quarantined record recovered after operator investigation. */
  void markRecovered(DeliveryPosition position, long recoveredAtUnixMs);
}

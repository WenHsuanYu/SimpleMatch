package com.simplematch.config.delivery;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** Reads current quarantine state from its service-owned durable store. */
public final class CriticalConsumerOperationalStatusProvider {
  private final String consumerName;
  private final QuarantineStore quarantineStore;
  private final Clock clock;

  /** Creates a provider for one named critical consumer. */
  public CriticalConsumerOperationalStatusProvider(
      String consumerName, QuarantineStore quarantineStore, Clock clock) {
    if (consumerName == null || consumerName.isBlank()) {
      throw new IllegalArgumentException("consumerName must not be blank");
    }
    this.consumerName = consumerName;
    this.quarantineStore = Objects.requireNonNull(quarantineStore, "quarantineStore");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Returns a fresh view backed by the durable quarantine store. */
  public CriticalConsumerOperationalStatus current(
      Map<Integer, Long> committedOffsets,
      Map<Integer, Long> oldestUnprocessedAgeMillis) {
    return new CriticalConsumerOperationalStatus(
        !quarantineStore.loadOpenPositions(consumerName).isEmpty(),
        committedOffsets,
        oldestUnprocessedAgeMillis,
        clock.instant());
  }
}

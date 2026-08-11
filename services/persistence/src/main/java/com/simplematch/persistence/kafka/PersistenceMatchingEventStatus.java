package com.simplematch.persistence.kafka;

import com.simplematch.config.delivery.DeliveryPosition;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Holds the compact critical-consumer status consumed by readiness adapters. */
public final class PersistenceMatchingEventStatus {
  /** The durable-consumer state that affects trading admission. */
  public enum State {
    READY,
    QUARANTINED
  }

  private final AtomicReference<State> state = new AtomicReference<>(State.READY);
  private final Map<Integer, Long> committedOffsets = new ConcurrentHashMap<>();
  private final AtomicReference<DeliveryPosition> quarantinePosition = new AtomicReference<>();

  /** Returns whether the critical Persistence consumer can continue receiving final events. */
  public State state() {
    return state.get();
  }

  /** Returns the latest local committed offset for each Matching partition. */
  public Map<Integer, Long> committedOffsets() {
    return Map.copyOf(committedOffsets);
  }

  /** Returns the exact blocked location when an operator must resolve a quarantine. */
  public java.util.Optional<DeliveryPosition> quarantinePosition() {
    return java.util.Optional.ofNullable(quarantinePosition.get());
  }

  /** Records a Kafka acknowledgement that followed a successful local transaction. */
  public void recordCommitted(int partition, long offset) {
    committedOffsets.merge(partition, offset, Math::max);
  }

  /** Marks one exact Kafka position as quarantined and therefore not ready. */
  public void recordQuarantined(DeliveryPosition position) {
    quarantinePosition.set(position);
    state.set(State.QUARANTINED);
  }

  /** Clears an operator-resolved quarantine without pretending the market has reopened. */
  public void recordRecovered(DeliveryPosition position) {
    if (quarantinePosition.compareAndSet(position, null)) {
      state.set(State.READY);
    }
  }
}

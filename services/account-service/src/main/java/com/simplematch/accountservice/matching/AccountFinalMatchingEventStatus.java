package com.simplematch.accountservice.matching;

import com.simplematch.config.delivery.DeliveryPosition;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Holds Account's compact final-event status without making an idle market appear unhealthy. */
public final class AccountFinalMatchingEventStatus {
  /** The state that Gateway readiness adapters need from Account's critical consumer. */
  public enum State {
    READY,
    QUARANTINED
  }

  private final AtomicReference<State> state = new AtomicReference<>(State.READY);
  private final Map<Integer, Long> committedOffsets = new ConcurrentHashMap<>();
  private final AtomicReference<DeliveryPosition> quarantinePosition = new AtomicReference<>();

  /** Returns the current readiness state. */
  public State state() {
    return state.get();
  }

  /** Returns one persisted Kafka position per Matching partition. */
  public Map<Integer, Long> committedOffsets() {
    return Map.copyOf(committedOffsets);
  }

  /** Returns the exact location that currently requires operator recovery. */
  public java.util.Optional<DeliveryPosition> quarantinePosition() {
    return java.util.Optional.ofNullable(quarantinePosition.get());
  }

  /** Records a successful post-transaction Kafka acknowledgement. */
  public void recordCommitted(int partition, long offset) {
    committedOffsets.merge(partition, offset, Math::max);
  }

  /** Marks the associated final-event partition unavailable until manual recovery. */
  public void recordQuarantined(DeliveryPosition position) {
    quarantinePosition.set(position);
    state.set(State.QUARANTINED);
  }

  /** Clears only the exact position that the operator resumed. */
  public void recordRecovered(DeliveryPosition position) {
    if (quarantinePosition.compareAndSet(position, null)) {
      state.set(State.READY);
    }
  }
}

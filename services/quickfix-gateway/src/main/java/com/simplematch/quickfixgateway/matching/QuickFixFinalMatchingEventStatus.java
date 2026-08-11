package com.simplematch.quickfixgateway.matching;

import com.simplematch.config.delivery.DeliveryPosition;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Holds Gateway final-event consumer state without treating an idle market as unhealthy. */
public final class QuickFixFinalMatchingEventStatus {
  /** The critical final-event consumer state used by Gateway readiness and admission adapters. */
  public enum State {
    READY,
    QUARANTINED
  }

  private final AtomicReference<State> state = new AtomicReference<>(State.READY);
  private final Map<Integer, Long> committedOffsets = new ConcurrentHashMap<>();
  private final AtomicReference<DeliveryPosition> quarantinePosition = new AtomicReference<>();

  /** Returns whether the Gateway critical final-event consumer is able to continue safely. */
  public State state() {
    return state.get();
  }

  /** Returns the Gateway's post-transaction acknowledged final-event offsets by partition. */
  public Map<Integer, Long> committedOffsets() {
    return Map.copyOf(committedOffsets);
  }

  /** Returns the exact record requiring an operator recovery, if the consumer is quarantined. */
  public java.util.Optional<DeliveryPosition> quarantinePosition() {
    return java.util.Optional.ofNullable(quarantinePosition.get());
  }

  /** Records a Kafka acknowledgement that followed durable inbox and intent persistence. */
  public void recordCommitted(int partition, long offset) {
    committedOffsets.merge(partition, offset, Math::max);
  }

  /** Marks an exact final-event record quarantined and no longer ready. */
  public void recordQuarantined(DeliveryPosition position) {
    quarantinePosition.set(position);
    state.set(State.QUARANTINED);
  }

  /** Clears only the exact operator-recovered quarantine record. */
  public void recordRecovered(DeliveryPosition position) {
    if (quarantinePosition.compareAndSet(position, null)) {
      state.set(State.READY);
    }
  }
}

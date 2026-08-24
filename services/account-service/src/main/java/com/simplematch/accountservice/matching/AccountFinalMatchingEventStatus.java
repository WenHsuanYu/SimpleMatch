package com.simplematch.accountservice.matching;

import com.simplematch.config.delivery.CriticalConsumerProgressTracker;
import com.simplematch.config.delivery.DeliveryPosition;
import java.time.Clock;
import java.util.Map;

/** Holds Account's compact final-event status without making an idle market appear unhealthy. */
public final class AccountFinalMatchingEventStatus {
  /** The state that Gateway readiness adapters need from Account's critical consumer. */
  public enum State {
    READY,
    QUARANTINED
  }

  private final CriticalConsumerProgressTracker progress;

  /** Creates status using the system UTC clock. */
  public AccountFinalMatchingEventStatus() {
    this(Clock.systemUTC());
  }

  /** Creates status using the supplied service clock. */
  public AccountFinalMatchingEventStatus(Clock clock) {
    progress = new CriticalConsumerProgressTracker(clock);
  }

  /** Returns the current readiness state. */
  public State state() {
    return progress.quarantinePosition().isPresent() ? State.QUARANTINED : State.READY;
  }

  /** Returns the next Kafka position to consume for each Matching partition. */
  public Map<Integer, Long> committedOffsets() {
    return progress.committedPositions();
  }

  /** Returns the age of the oldest currently unprocessed record in each partition. */
  public Map<Integer, Long> oldestUnprocessedAgeMillis() {
    return progress.oldestUnprocessedAgeMillis();
  }

  /** Returns the exact location that currently requires operator recovery. */
  public java.util.Optional<DeliveryPosition> quarantinePosition() {
    return progress.quarantinePosition();
  }

  /** Marks one Kafka record pending before Account applies it. */
  public void recordPending(int partition, long offset, long recordTimestampUnixMs) {
    progress.recordPending(partition, offset, recordTimestampUnixMs);
  }

  /** Records the processed record offset as the next Kafka position after acknowledgement. */
  public void recordCommitted(int partition, long offset) {
    progress.recordCommitted(partition, offset);
  }

  /** Marks the associated final-event partition unavailable until manual recovery. */
  public void recordQuarantined(DeliveryPosition position) {
    progress.recordQuarantined(position);
  }

  /** Clears only the exact position that the operator resumed. */
  public void recordRecovered(DeliveryPosition position) {
    progress.recordRecovered(position);
  }
}

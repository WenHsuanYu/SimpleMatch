package com.simplematch.persistence.kafka;

import com.simplematch.config.delivery.CriticalConsumerProgressTracker;
import com.simplematch.config.delivery.DeliveryPosition;
import java.time.Clock;
import java.util.Map;

/** Holds the compact critical-consumer status consumed by readiness adapters. */
public final class PersistenceMatchingEventStatus {
  /** The durable-consumer state that affects trading admission. */
  public enum State {
    READY,
    QUARANTINED
  }

  private final CriticalConsumerProgressTracker progress;

  /** Creates status using the system UTC clock. */
  public PersistenceMatchingEventStatus() {
    this(Clock.systemUTC());
  }

  /** Creates status using the supplied service clock. */
  public PersistenceMatchingEventStatus(Clock clock) {
    progress = new CriticalConsumerProgressTracker(clock);
  }

  /** Returns whether the critical Persistence consumer can continue receiving final events. */
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

  /** Returns the exact blocked location when an operator must resolve a quarantine. */
  public java.util.Optional<DeliveryPosition> quarantinePosition() {
    return progress.quarantinePosition();
  }

  /** Marks one Kafka record pending before Persistence applies it. */
  public void recordPending(int partition, long offset, long recordTimestampUnixMs) {
    progress.recordPending(partition, offset, recordTimestampUnixMs);
  }

  /** Records the processed record offset as the next Kafka position after acknowledgement. */
  public void recordCommitted(int partition, long offset) {
    progress.recordCommitted(partition, offset);
  }

  /** Marks one exact Kafka position as quarantined and therefore not ready. */
  public void recordQuarantined(DeliveryPosition position) {
    progress.recordQuarantined(position);
  }

  /** Clears an operator-resolved quarantine without pretending the market has reopened. */
  public void recordRecovered(DeliveryPosition position) {
    progress.recordRecovered(position);
  }
}

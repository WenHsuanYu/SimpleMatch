package com.simplematch.quickfixgateway.matching;

import com.simplematch.config.delivery.CriticalConsumerProgressTracker;
import com.simplematch.config.delivery.DeliveryPosition;
import java.time.Clock;
import java.util.Map;

/** Holds Gateway final-event consumer state without treating an idle market as unhealthy. */
public final class QuickFixFinalMatchingEventStatus {
  /** The critical final-event consumer state used by Gateway readiness and admission adapters. */
  public enum State {
    READY,
    QUARANTINED
  }

  private final CriticalConsumerProgressTracker progress;

  /** Creates status using the system UTC clock. */
  public QuickFixFinalMatchingEventStatus() {
    this(Clock.systemUTC());
  }

  /** Creates status using the supplied service clock. */
  public QuickFixFinalMatchingEventStatus(Clock clock) {
    progress = new CriticalConsumerProgressTracker(clock);
  }

  /** Returns whether the Gateway critical final-event consumer is able to continue safely. */
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

  /** Returns the exact record requiring an operator recovery, if the consumer is quarantined. */
  public java.util.Optional<DeliveryPosition> quarantinePosition() {
    return progress.quarantinePosition();
  }

  /** Marks one Kafka record pending before the Gateway plans durable FIX delivery. */
  public void recordPending(int partition, long offset, long recordTimestampUnixMs) {
    progress.recordPending(partition, offset, recordTimestampUnixMs);
  }

  /** Records the processed record offset as the next Kafka position after acknowledgement. */
  public void recordCommitted(int partition, long offset) {
    progress.recordCommitted(partition, offset);
  }

  /** Marks an exact final-event record quarantined and no longer ready. */
  public void recordQuarantined(DeliveryPosition position) {
    progress.recordQuarantined(position);
  }

  /** Clears only the exact operator-recovered quarantine record. */
  public void recordRecovered(DeliveryPosition position) {
    progress.recordRecovered(position);
  }
}

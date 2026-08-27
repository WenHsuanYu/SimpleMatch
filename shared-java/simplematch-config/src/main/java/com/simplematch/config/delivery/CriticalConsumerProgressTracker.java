package com.simplematch.config.delivery;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Tracks transport-level progress and pending work for one critical Kafka consumer. */
public final class CriticalConsumerProgressTracker {
  private final Clock clock;
  private final Map<Integer, Long> committedPositions = new ConcurrentHashMap<>();
  private final Map<Integer, PendingRecord> pendingRecords = new ConcurrentHashMap<>();
  private final AtomicReference<DeliveryPosition> quarantinePosition = new AtomicReference<>();

  /** Creates a tracker using the system UTC clock. */
  public CriticalConsumerProgressTracker() {
    this(Clock.systemUTC());
  }

  /** Creates a tracker using the supplied service clock. */
  public CriticalConsumerProgressTracker(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Returns the next Kafka position to consume for each observed partition. */
  public Map<Integer, Long> committedPositions() {
    return Map.copyOf(committedPositions);
  }

  /** Returns the age of the oldest currently unprocessed record in each partition. */
  public Map<Integer, Long> oldestUnprocessedAgeMillis() {
    final long now = clock.millis();
    final Map<Integer, Long> ages = new HashMap<>();
    pendingRecords.forEach(
        (partition, pending) ->
            ages.put(partition, ageMillis(now, pending.firstObservedAtUnixMs())));
    return Map.copyOf(ages);
  }

  /** Returns the exact Kafka record that currently requires operator recovery. */
  public Optional<DeliveryPosition> quarantinePosition() {
    return Optional.ofNullable(quarantinePosition.get());
  }

  /** Marks one Kafka record pending before parsing, validation, or durable application begins. */
  public void recordPending(int partition, long offset, long recordTimestampUnixMs) {
    requirePosition(partition, offset);
    final long observedAtUnixMs =
        recordTimestampUnixMs >= 0 ? recordTimestampUnixMs : clock.millis();
    final PendingRecord candidate = new PendingRecord(offset, observedAtUnixMs);
    pendingRecords.compute(
        partition,
        (ignored, current) -> current == null ? candidate : earlier(current, candidate));
  }

  /** Records a successfully acknowledged record and clears completed pending work. */
  public void recordCommitted(int partition, long offset) {
    requirePosition(partition, offset);
    committedPositions.merge(partition, Math.addExact(offset, 1L), Math::max);
    pendingRecords.computeIfPresent(
        partition, (ignored, current) -> current.offset() <= offset ? null : current);
  }

  /** Records the exact position of a quarantined critical record. */
  public void recordQuarantined(DeliveryPosition position) {
    quarantinePosition.set(Objects.requireNonNull(position, "position"));
  }

  /** Clears only the exact quarantine position that an operator resumed. */
  public void recordRecovered(DeliveryPosition position) {
    quarantinePosition.compareAndSet(Objects.requireNonNull(position, "position"), null);
  }

  private PendingRecord earlier(PendingRecord current, PendingRecord candidate) {
    if (candidate.offset() < current.offset()) {
      return candidate;
    }
    if (candidate.offset() > current.offset()) {
      return current;
    }
    return new PendingRecord(
        current.offset(),
        Math.min(current.firstObservedAtUnixMs(), candidate.firstObservedAtUnixMs()));
  }

  private long ageMillis(long now, long firstObservedAtUnixMs) {
    return now <= firstObservedAtUnixMs ? 0L : now - firstObservedAtUnixMs;
  }

  private void requirePosition(int partition, long offset) {
    if (partition < 0 || offset < 0) {
      throw new IllegalArgumentException("critical consumer Kafka position is invalid");
    }
  }

  private record PendingRecord(long offset, long firstObservedAtUnixMs) {}
}

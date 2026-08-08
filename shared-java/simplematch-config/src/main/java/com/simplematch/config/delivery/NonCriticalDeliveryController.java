package com.simplematch.config.delivery;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Applies delayed retry and dead-letter handling to rebuildable, non-critical projections. */
public final class NonCriticalDeliveryController {
  private final String consumerName;
  private final int maximumAttempts;
  private final Duration delay;
  private final Clock clock;
  private final DeadLetterStore deadLetterStore;
  private final DeliveryMetrics metrics;
  private final Map<DeliveryPosition, Integer> attempts = new HashMap<>();

  /** Creates a non-critical policy that never blocks authoritative partitions. */
  public NonCriticalDeliveryController(
      String consumerName,
      int maximumAttempts,
      Duration delay,
      Clock clock,
      DeadLetterStore deadLetterStore) {
    this(consumerName, maximumAttempts, delay, clock, deadLetterStore, DeliveryMetrics.noop());
  }

  /** Creates a non-critical policy with an optional telemetry sink. */
  public NonCriticalDeliveryController(
      String consumerName,
      int maximumAttempts,
      Duration delay,
      Clock clock,
      DeadLetterStore deadLetterStore,
      DeliveryMetrics metrics) {
    if (consumerName == null || consumerName.isBlank()) {
      throw new IllegalArgumentException("consumer name must not be blank");
    }
    if (maximumAttempts <= 0) {
      throw new IllegalArgumentException("maximum attempts must be positive");
    }
    this.delay = Objects.requireNonNull(delay, "retry delay");
    if (this.delay.isNegative() || this.delay.isZero()) {
      throw new IllegalArgumentException("retry delay must be positive");
    }
    this.consumerName = consumerName;
    this.maximumAttempts = maximumAttempts;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.deadLetterStore = Objects.requireNonNull(deadLetterStore, "dead-letter store");
    this.metrics = Objects.requireNonNull(metrics, "delivery metrics");
  }

  /** Records a successful projection and permits the original offset to commit. */
  public synchronized DeliveryDecision onSuccess(DeliveryRecord record) {
    attempts.remove(record.position());
    return DeliveryDecision.COMMIT;
  }

  /** Schedules a later retry or records a dead-letter outcome after the bounded budget. */
  public synchronized NonCriticalDeliveryResult onFailure(
      DeliveryRecord record, Throwable failure) {
    Objects.requireNonNull(record, "delivery record");
    Objects.requireNonNull(failure, "delivery failure");
    final int attempt = attempts.merge(record.position(), 1, Integer::sum);
    if (attempt >= maximumAttempts) {
      deadLetterStore.save(
          new DeadLetterEvidence(
              consumerName,
              record,
              attempt,
              failure.getMessage() == null
                  ? failure.getClass().getSimpleName()
                  : failure.getMessage(),
              clock.instant()));
      metrics.increment(DeliveryMetric.DEAD_LETTER, consumerName, record.position());
      attempts.remove(record.position());
      return new NonCriticalDeliveryResult(DeliveryDecision.COMMIT, null);
    }
    metrics.increment(DeliveryMetric.RETRY, consumerName, record.position());
    return new NonCriticalDeliveryResult(
        DeliveryDecision.COMMIT, clock.instant().plus(delay));
  }

  /** Records the next delayed retry deadline without changing authoritative consumer state. */
  public record NonCriticalDeliveryResult(DeliveryDecision decision, java.time.Instant retryAt) {}
}

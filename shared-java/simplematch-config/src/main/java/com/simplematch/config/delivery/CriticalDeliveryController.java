package com.simplematch.config.delivery;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Enforces in-place retry, partition pause, and same-offset recovery for critical consumers. */
public final class CriticalDeliveryController {
  private final String consumerName;
  private final int maximumAttempts;
  private final String recoveryInstructions;
  private final Clock clock;
  private final QuarantineStore quarantineStore;
  private final DeliveryMetrics metrics;
  private final Map<DeliveryPosition.TopicPartition, FailureState> failures = new HashMap<>();
  private final Map<DeliveryPosition.TopicPartition, Long> pausedOffsets = new HashMap<>();

  /** Creates a controller with a bounded retry budget and durable quarantine store. */
  public CriticalDeliveryController(
      String consumerName,
      int maximumAttempts,
      String recoveryInstructions,
      Clock clock,
      QuarantineStore quarantineStore) {
    this(
        consumerName,
        maximumAttempts,
        recoveryInstructions,
        clock,
        quarantineStore,
        DeliveryMetrics.noop());
  }

  /** Creates a controller with durable quarantine and an optional telemetry sink. */
  public CriticalDeliveryController(
      String consumerName,
      int maximumAttempts,
      String recoveryInstructions,
      Clock clock,
      QuarantineStore quarantineStore,
      DeliveryMetrics metrics) {
    if (consumerName == null || consumerName.isBlank()) {
      throw new IllegalArgumentException("consumer name must not be blank");
    }
    if (maximumAttempts <= 0) {
      throw new IllegalArgumentException("maximum attempts must be positive");
    }
    if (recoveryInstructions == null || recoveryInstructions.isBlank()) {
      throw new IllegalArgumentException("recovery instructions must not be blank");
    }
    this.consumerName = consumerName;
    this.maximumAttempts = maximumAttempts;
    this.recoveryInstructions = recoveryInstructions;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.quarantineStore = Objects.requireNonNull(quarantineStore, "quarantine store");
    this.metrics = Objects.requireNonNull(metrics, "delivery metrics");
  }

  /** Restores durable quarantines before the consumer begins processing records. */
  public synchronized void restoreQuarantines(List<DeliveryPosition> positions) {
    Objects.requireNonNull(positions, "quarantine positions");
    for (DeliveryPosition position : positions) {
      final DeliveryPosition safePosition =
          Objects.requireNonNull(position, "quarantine position");
      pausedOffsets.merge(
          safePosition.topicPartition(), safePosition.offset(), Math::min);
    }
  }

  /** Records success and permits an offset commit only when its partition is not blocked. */
  public synchronized DeliveryDecision onSuccess(DeliveryRecord record) {
    Objects.requireNonNull(record, "delivery record");
    final Long pausedOffset = pausedOffsets.get(record.position().topicPartition());
    if (pausedOffset != null && pausedOffset <= record.position().offset()) {
      return DeliveryDecision.BLOCKED;
    }
    final FailureState failure = failures.get(record.position().topicPartition());
    if (failure != null
        && failure.failedOffset != null
        && failure.failedOffset <= record.position().offset()) {
      return DeliveryDecision.BLOCKED;
    }
    failures.remove(record.position().topicPartition());
    return DeliveryDecision.COMMIT;
  }

  /** Records one failure without ever granting permission to commit the failed offset. */
  public synchronized DeliveryDecision onFailure(DeliveryRecord record, Throwable failure) {
    Objects.requireNonNull(record, "delivery record");
    Objects.requireNonNull(failure, "delivery failure");
    final DeliveryPosition.TopicPartition topicPartition = record.position().topicPartition();
    final Long pausedOffset = pausedOffsets.get(topicPartition);
    if (pausedOffset != null && pausedOffset <= record.position().offset()) {
      return DeliveryDecision.BLOCKED;
    }

    final FailureState state =
        failures.computeIfAbsent(topicPartition, ignored -> new FailureState());
    if (state.failedOffset == null) {
      state.failedOffset = record.position().offset();
    }
    if (state.failedOffset.longValue() != record.position().offset()) {
      return DeliveryDecision.BLOCKED;
    }

    state.attempts.add(
        new RetryAttempt(state.attempts.size() + 1, clock.instant(), failureMessage(failure)));
    if (state.attempts.size() < maximumAttempts) {
      metrics.increment(DeliveryMetric.RETRY, consumerName, record.position());
      return DeliveryDecision.RETRY_IN_PLACE;
    }

    final QuarantineEvidence evidence =
        new QuarantineEvidence(
            consumerName,
            record,
            state.attempts,
            failureMessage(failure),
            recoveryInstructions,
            clock.instant());
    quarantineStore.save(evidence);
    metrics.increment(DeliveryMetric.QUARANTINE, consumerName, record.position());
    pausedOffsets.put(topicPartition, record.position().offset());
    return DeliveryDecision.QUARANTINED;
  }

  /** Records an inbox-deduplicated delivery without changing partition state. */
  public synchronized void recordDuplicate(DeliveryRecord record) {
    Objects.requireNonNull(record, "delivery record");
    metrics.increment(DeliveryMetric.DUPLICATE, consumerName, record.position());
  }

  /** Resumes exactly the quarantined offset after the operator has corrected the cause. */
  public synchronized void resume(DeliveryPosition position, long recoveredAtUnixMs) {
    Objects.requireNonNull(position, "delivery position");
    final DeliveryPosition.TopicPartition topicPartition = position.topicPartition();
    final Long pausedOffset = pausedOffsets.get(topicPartition);
    if (pausedOffset == null || pausedOffset.longValue() != position.offset()) {
      throw new IllegalArgumentException("recovery must target the quarantined offset");
    }
    quarantineStore.markRecovered(position, recoveredAtUnixMs);
    pausedOffsets.remove(topicPartition);
    failures.remove(topicPartition);
  }

  /** Returns whether a partition is paused at a failed offset. */
  public synchronized boolean isPaused(DeliveryPosition.TopicPartition topicPartition) {
    return pausedOffsets.containsKey(topicPartition);
  }

  /** Returns the exact offset that blocks a partition, when one exists. */
  public synchronized java.util.OptionalLong pausedOffset(
      DeliveryPosition.TopicPartition topicPartition) {
    final Long offset = pausedOffsets.get(topicPartition);
    return offset == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(offset);
  }

  private String failureMessage(Throwable failure) {
    final String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }

  private static final class FailureState {
    private Long failedOffset;
    private final List<RetryAttempt> attempts = new ArrayList<>();
  }
}

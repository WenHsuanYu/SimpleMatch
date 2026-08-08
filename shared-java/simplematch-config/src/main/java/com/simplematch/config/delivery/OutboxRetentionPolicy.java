package com.simplematch.config.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Calculates a conservative outbox cleanup boundary from CDC and investigation watermarks. */
public final class OutboxRetentionPolicy {
  private final Duration cdcSafetyWindow;

  /** Creates a policy that keeps rows inside the configured CDC safety window. */
  public OutboxRetentionPolicy(Duration cdcSafetyWindow) {
    this.cdcSafetyWindow = Objects.requireNonNull(cdcSafetyWindow, "cdc safety window");
    if (cdcSafetyWindow.isNegative() || cdcSafetyWindow.isZero()) {
      throw new IllegalArgumentException("cdc safety window must be positive");
    }
  }

  /**
   * Returns the exclusive creation-time boundary before which rows may be deleted.
   *
   * <p>No boundary is returned until CDC has published through a durable watermark. The oldest
   * row required for replay or operator investigation always narrows the deletion boundary.</p>
   */
  public Optional<Instant> deletableBefore(RetentionWatermark watermark) {
    Objects.requireNonNull(watermark, "retention watermark");
    if (watermark.cdcPublishedThrough() == null) {
      return Optional.empty();
    }
    final Instant cdcBoundary = watermark.cdcPublishedThrough().minus(cdcSafetyWindow);
    final Instant requiredBoundary = watermark.oldestRequiredEventAt();
    if (requiredBoundary == null) {
      return Optional.of(cdcBoundary);
    }
    return Optional.of(cdcBoundary.isBefore(requiredBoundary) ? cdcBoundary : requiredBoundary);
  }

  /** Returns whether one immutable outbox row is outside every active safety boundary. */
  public boolean mayDelete(Instant createdAt, RetentionWatermark watermark) {
    Objects.requireNonNull(createdAt, "createdAt");
    return deletableBefore(watermark).map(createdAt::isBefore).orElse(false);
  }

  /** Durable watermarks needed before any cleanup can be authorized. */
  public record RetentionWatermark(
      Instant cdcPublishedThrough, Instant oldestRequiredEventAt) {
    /** Validates optional timestamps and their ordering. */
    public RetentionWatermark {
      if (cdcPublishedThrough != null
          && oldestRequiredEventAt != null
          && oldestRequiredEventAt.isAfter(cdcPublishedThrough)) {
        throw new IllegalArgumentException(
            "oldest required event cannot be after the CDC watermark");
      }
    }
  }
}

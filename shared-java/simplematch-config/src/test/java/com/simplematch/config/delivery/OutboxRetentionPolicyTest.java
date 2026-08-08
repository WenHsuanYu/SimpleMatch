package com.simplematch.config.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboxRetentionPolicyTest {
  private static final Instant PUBLISHED_THROUGH = Instant.parse("2026-08-04T00:10:00Z");
  private final OutboxRetentionPolicy policy =
      new OutboxRetentionPolicy(Duration.ofMinutes(5));

  @Test
  void doesNotAuthorizeCleanupBeforeCdcPublishesAWatermark() {
    assertThat(
            policy.deletableBefore(
                new OutboxRetentionPolicy.RetentionWatermark(null, null)))
        .isEmpty();
  }

  @Test
  void keepsRowsInsideTheCdcSafetyWindow() {
    final var watermark =
        new OutboxRetentionPolicy.RetentionWatermark(PUBLISHED_THROUGH, null);

    assertThat(policy.deletableBefore(watermark))
        .contains(Instant.parse("2026-08-04T00:05:00Z"));
    assertThat(policy.mayDelete(Instant.parse("2026-08-04T00:04:59Z"), watermark)).isTrue();
    assertThat(policy.mayDelete(Instant.parse("2026-08-04T00:05:00Z"), watermark)).isFalse();
  }

  @Test
  void narrowsCleanupWhenReplayOrInvestigationNeedsAnOlderRow() {
    final var watermark =
        new OutboxRetentionPolicy.RetentionWatermark(
            PUBLISHED_THROUGH, Instant.parse("2026-08-04T00:03:00Z"));

    assertThat(policy.deletableBefore(watermark))
        .contains(Instant.parse("2026-08-04T00:03:00Z"));
    assertThat(policy.mayDelete(Instant.parse("2026-08-04T00:03:00Z"), watermark)).isFalse();
  }

  @Test
  void rejectsAnImpossibleRetentionWatermark() {
    assertThatThrownBy(
            () ->
                new OutboxRetentionPolicy.RetentionWatermark(
                    Instant.parse("2026-08-04T00:05:00Z"),
                    Instant.parse("2026-08-04T00:06:00Z")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be after");
  }
}

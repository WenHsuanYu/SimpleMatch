package com.simplematch.riskservice.admission;

import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.BACKLOG_EXCEEDED;
import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.METRIC_INVALID;
import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.METRIC_STALE;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies durable CDC lag and freshness admission boundaries. */
class CdcLagBackpressurePolicyTest {

  private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");

  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static final Duration MAXIMUM_METRIC_AGE = Duration.ofSeconds(30);

  @Test
  void permitsFreshLagAtConfiguredBound() {
    final CdcLagReader reader = _ -> new CdcLagSnapshot(10, NOW.minusSeconds(30));

    final CdcLagBackpressurePolicy policy = policy(reader, 10);

    assertThatCode(policy::check).doesNotThrowAnyException();
  }

  @Test
  void rejectsLagAboveConfiguredBound() {
    final CdcLagReader reader = _ -> new CdcLagSnapshot(11, NOW.minusSeconds(1));

    final CdcLagBackpressurePolicy policy = policy(reader, 10);

    assertThatThrownBy(policy::check)
        .isInstanceOf(AdmissionBackpressureException.class)
        .satisfies(
            error -> {
              final AdmissionBackpressureException failure = (AdmissionBackpressureException) error;

              org.assertj.core.api.Assertions.assertThat(failure.reason())
                  .isEqualTo(BACKLOG_EXCEEDED);
            });
  }

  @Test
  void rejectsMetricOlderThanMaximumAge() {
    final CdcLagReader reader = _ -> new CdcLagSnapshot(0, NOW.minusSeconds(31));

    final CdcLagBackpressurePolicy policy = policy(reader, 10);

    assertThatThrownBy(policy::check)
        .isInstanceOf(AdmissionBackpressureException.class)
        .satisfies(
            error -> {
              final AdmissionBackpressureException failure = (AdmissionBackpressureException) error;

              org.assertj.core.api.Assertions.assertThat(failure.reason()).isEqualTo(METRIC_STALE);
            });
  }

  @Test
  void rejectsMetricTimestampInFuture() {
    final CdcLagReader reader = _ -> new CdcLagSnapshot(0, NOW.plusSeconds(1));

    final CdcLagBackpressurePolicy policy = policy(reader, 10);

    assertThatThrownBy(policy::check)
        .isInstanceOf(AdmissionBackpressureException.class)
        .satisfies(
            error -> {
              final AdmissionBackpressureException failure = (AdmissionBackpressureException) error;

              org.assertj.core.api.Assertions.assertThat(failure.reason())
                  .isEqualTo(METRIC_INVALID);
            });
  }

  @Test
  void readsFreshSnapshotForEveryCheck() {
    final AtomicReference<CdcLagSnapshot> snapshot =
        new AtomicReference<>(new CdcLagSnapshot(0, NOW.minusSeconds(1)));

    final CdcLagReader reader = _ -> snapshot.get();

    final CdcLagBackpressurePolicy policy = policy(reader, 10);

    assertThatCode(policy::check).doesNotThrowAnyException();

    snapshot.set(new CdcLagSnapshot(11, NOW.minusSeconds(1)));

    assertThatThrownBy(policy::check).isInstanceOf(AdmissionBackpressureException.class);
  }

  private CdcLagBackpressurePolicy policy(CdcLagReader reader, long maximumLag) {

    return new CdcLagBackpressurePolicy(
        reader, "orders.validated", maximumLag, MAXIMUM_METRIC_AGE, CLOCK);
  }
}

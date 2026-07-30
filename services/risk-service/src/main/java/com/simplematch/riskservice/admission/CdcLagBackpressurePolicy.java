package com.simplematch.riskservice.admission;

import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.BACKLOG_EXCEEDED;
import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.METRIC_INVALID;
import static com.simplematch.riskservice.admission.AdmissionBackpressureException.Reason.METRIC_STALE;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Rejects new admissions when the observed durable publication lag exceeds a bound. */
public final class CdcLagBackpressurePolicy implements AdmissionBackpressurePolicy {

  private final CdcLagReader lagReader;
  private final String metricName;
  private final long maximumLag;
  private final Duration maximumMetricAge;
  private final Clock clock;

  /**
   * Creates a policy backed by a live CDC delivery-lag metric.
   *
   * @param lagReader metric persistence adapter
   * @param metricName stable CDC metric identity
   * @param maximumLag maximum allowed durable event backlog
   * @param maximumMetricAge maximum permitted age of the metric
   * @param clock time source used for deterministic freshness checks
   */
  public CdcLagBackpressurePolicy(
      CdcLagReader lagReader,
      String metricName,
      long maximumLag,
      Duration maximumMetricAge,
      Clock clock) {

    this.lagReader = Objects.requireNonNull(lagReader, "lagReader");

    if (metricName == null || metricName.isBlank()) {
      throw new IllegalArgumentException("metricName must not be blank");
    }

    if (maximumLag < 0) {
      throw new IllegalArgumentException("maximumLag must not be negative");
    }

    Objects.requireNonNull(maximumMetricAge, "maximumMetricAge");

    if (maximumMetricAge.isNegative() || maximumMetricAge.isZero()) {
      throw new IllegalArgumentException("maximumMetricAge must be positive");
    }

    this.metricName = metricName;
    this.maximumLag = maximumLag;
    this.maximumMetricAge = maximumMetricAge;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void check() {
    final CdcLagSnapshot snapshot = lagReader.read(metricName);

    validateLag(snapshot);
    validateFreshness(snapshot);
  }

  private void validateLag(CdcLagSnapshot snapshot) {
    if (snapshot.lagEvents() > maximumLag) {
      throw new AdmissionBackpressureException(
          BACKLOG_EXCEEDED, "Durable admission backlog exceeds its safe bound");
    }
  }

  private void validateFreshness(CdcLagSnapshot snapshot) {
    final Instant now = clock.instant();

    if (snapshot.updatedAt().isAfter(now)) {
      throw new AdmissionBackpressureException(
          METRIC_INVALID, "CDC lag metric timestamp is in the future");
    }

    final Duration metricAge = Duration.between(snapshot.updatedAt(), now);

    if (metricAge.compareTo(maximumMetricAge) > 0) {
      throw new AdmissionBackpressureException(
          METRIC_STALE, "CDC lag metric is older than its permitted age");
    }
  }
}

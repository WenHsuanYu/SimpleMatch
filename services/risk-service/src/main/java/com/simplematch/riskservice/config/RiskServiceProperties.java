package com.simplematch.riskservice.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed operational configuration owned by risk-service.
 *
 * @param admission admission safety-bound configuration
 */
@ConfigurationProperties("simplematch.risk-service")
public record RiskServiceProperties(@DefaultValue AdmissionProperties admission) {

  /** Applies defensive validation after Spring property binding. */
  public RiskServiceProperties {
    Objects.requireNonNull(admission, "admission");
  }

  /**
   * Admission backpressure settings.
   *
   * @param cdcMetricName durable CDC metric identity
   * @param maximumCdcLagEvents largest permitted durable event backlog
   * @param maximumMetricAge longest permitted time since metric refresh
   */
  public record AdmissionProperties(
      @DefaultValue("orders.validated") String cdcMetricName,
      @DefaultValue("10000") long maximumCdcLagEvents,
      @DefaultValue("30s") Duration maximumMetricAge) {

    /** Validates operational safety settings. */
    public AdmissionProperties {
      if (cdcMetricName == null || cdcMetricName.isBlank()) {
        throw new IllegalArgumentException("cdcMetricName must not be blank");
      }

      if (maximumCdcLagEvents < 0) {
        throw new IllegalArgumentException("maximumCdcLagEvents must not be negative");
      }

      Objects.requireNonNull(maximumMetricAge, "maximumMetricAge");

      if (maximumMetricAge.isNegative() || maximumMetricAge.isZero()) {
        throw new IllegalArgumentException("maximumMetricAge must be positive");
      }
    }
  }
}

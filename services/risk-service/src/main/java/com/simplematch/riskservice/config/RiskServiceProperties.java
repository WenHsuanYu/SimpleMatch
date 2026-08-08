package com.simplematch.riskservice.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed operational configuration owned by risk-service.
 *
 * @param admission admission safety-bound configuration
 * @param routingPolicyConsumer critical Routing Policy consumer configuration
 */
@ConfigurationProperties("simplematch.risk-service")
public record RiskServiceProperties(
    @DefaultValue AdmissionProperties admission,
    @DefaultValue RoutingPolicyConsumerProperties routingPolicyConsumer) {

  /** Applies defensive validation after Spring property binding. */
  public RiskServiceProperties {
    Objects.requireNonNull(admission, "admission");
    Objects.requireNonNull(routingPolicyConsumer, "routingPolicyConsumer");
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

  /** Critical-consumer retry and recovery settings for the Risk-local policy projection. */
  public record RoutingPolicyConsumerProperties(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("3") int maximumAttempts,
      @DefaultValue(
          "Correct the policy projection, then resume the same topic partition and offset.")
          String recoveryInstructions) {
    /** Validates bounded retry and operator recovery configuration. */
    public RoutingPolicyConsumerProperties {
      if (maximumAttempts <= 0) {
        throw new IllegalArgumentException("routing policy maximum attempts must be positive");
      }
      if (recoveryInstructions == null || recoveryInstructions.isBlank()) {
        throw new IllegalArgumentException("routing policy recovery instructions are required");
      }
    }
  }
}

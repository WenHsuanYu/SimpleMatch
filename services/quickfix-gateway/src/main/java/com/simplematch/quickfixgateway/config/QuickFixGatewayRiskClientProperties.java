package com.simplematch.quickfixgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded synchronous risk-client resilience settings. */
@ConfigurationProperties("simplematch.quickfix-gateway.risk-client")
public record QuickFixGatewayRiskClientProperties(
    Integer deadlineMillis, RetryProperties retry, BreakerProperties breaker) {
  /** Normalizes the risk-client resilience settings to bounded defaults. */
  public QuickFixGatewayRiskClientProperties {
    deadlineMillis = positiveOrDefault(deadlineMillis, 1_500);
    retry = retry == null ? RetryProperties.defaults() : retry;
    breaker = breaker == null ? BreakerProperties.defaults() : breaker;
  }

  /** Retry settings for transient synchronous risk calls. */
  public record RetryProperties(Integer maxAttempts, Integer backoffMillis) {
    /** Normalizes retry settings to their configured defaults. */
    public RetryProperties {
      maxAttempts = positiveOrDefault(maxAttempts, 2);
      backoffMillis = nonNegativeOrDefault(backoffMillis, 50);
    }

    static RetryProperties defaults() {
      return new RetryProperties(null, null);
    }
  }

  /** Circuit-breaker settings for unavailable risk-service dependencies. */
  public record BreakerProperties(Integer consecutiveFailures, Integer openDurationMillis) {
    /** Normalizes circuit-breaker settings to their configured defaults. */
    public BreakerProperties {
      consecutiveFailures = positiveOrDefault(consecutiveFailures, 3);
      openDurationMillis = positiveOrDefault(openDurationMillis, 1_000);
    }

    static BreakerProperties defaults() {
      return new BreakerProperties(null, null);
    }
  }

  private static Integer positiveOrDefault(Integer value, int fallback) {
    return value == null ? Integer.valueOf(fallback) : value;
  }

  private static Integer nonNegativeOrDefault(Integer value, int fallback) {
    return value == null ? Integer.valueOf(fallback) : value;
  }
}

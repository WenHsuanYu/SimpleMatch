package com.simplematch.quickfixgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings owned by the QuickFIX gateway runtime. */
@ConfigurationProperties("simplematch.quickfix-gateway")
public record QuickFixGatewayProperties(
    String quickfixConfigPath,
    String walPath,
    String ownerId,
    Boolean acceptorEnabled,
    Boolean dataPlaneEnabled,
    Boolean compatibilityPublishEnabled,
    Boolean replayEnabled,
    RiskClientProperties riskClient) {
  public QuickFixGatewayProperties {
    quickfixConfigPath = defaultString(quickfixConfigPath, "config/quickfix/acceptor.cfg");
    walPath = defaultString(walPath, "data/quickfix/wal/inbound.wal");
    ownerId = defaultString(ownerId, "quickfix-gateway-0");
    acceptorEnabled = defaultBoolean(acceptorEnabled, true);
    dataPlaneEnabled = defaultBoolean(dataPlaneEnabled, true);
    compatibilityPublishEnabled = defaultBoolean(compatibilityPublishEnabled, false);
    replayEnabled = defaultBoolean(replayEnabled, true);
    riskClient = riskClient == null ? RiskClientProperties.defaults() : riskClient;
  }

  /** Bounded synchronous risk-client resilience policy. */
  public record RiskClientProperties(
      Integer deadlineMillis, RetryProperties retry, BreakerProperties breaker) {
    public RiskClientProperties {
      deadlineMillis = positiveOrDefault(deadlineMillis, 1_500);
      retry = retry == null ? RetryProperties.defaults() : retry;
      breaker = breaker == null ? BreakerProperties.defaults() : breaker;
    }

    static RiskClientProperties defaults() {
      return new RiskClientProperties(null, null, null);
    }
  }

  /** Retry settings for transient synchronous risk calls. */
  public record RetryProperties(Integer maxAttempts, Integer backoffMillis) {
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
    public BreakerProperties {
      consecutiveFailures = positiveOrDefault(consecutiveFailures, 3);
      openDurationMillis = positiveOrDefault(openDurationMillis, 1_000);
    }

    static BreakerProperties defaults() {
      return new BreakerProperties(null, null);
    }
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Integer positiveOrDefault(Integer value, int fallback) {
    return value == null ? Integer.valueOf(fallback) : value;
  }

  private static Integer nonNegativeOrDefault(Integer value, int fallback) {
    return value == null ? Integer.valueOf(fallback) : value;
  }

  private static Boolean defaultBoolean(Boolean value, boolean fallback) {
    return value == null ? Boolean.valueOf(fallback) : value;
  }
}

package com.simplematch.quickfixgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime policy for the rebuildable QuickFIX execution projection. */
@ConfigurationProperties("simplematch.quickfix-gateway.execution-projection")
public record QuickFixGatewayExecutionProjectionProperties(
    int maximumAttempts, long retryDelayMillis, String deadLetterTopic) {
  /** Applies safe local defaults for non-critical projection delivery. */
  public QuickFixGatewayExecutionProjectionProperties {
    maximumAttempts = maximumAttempts == 0 ? 3 : maximumAttempts;
    retryDelayMillis = retryDelayMillis == 0L ? 1_000L : retryDelayMillis;
    deadLetterTopic = defaultTopic(deadLetterTopic);
    if (maximumAttempts <= 0) {
      throw new IllegalArgumentException("execution projection maximum attempts must be positive");
    }
    if (retryDelayMillis <= 0) {
      throw new IllegalArgumentException("execution projection retry delay must be positive");
    }
  }

  private static String defaultTopic(String topic) {
    return topic == null || topic.isBlank() ? "matching.executions.dlq" : topic;
  }
}

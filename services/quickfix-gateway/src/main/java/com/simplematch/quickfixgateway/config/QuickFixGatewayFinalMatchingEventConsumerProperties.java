package com.simplematch.quickfixgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds the Gateway's critical final Matching Event delivery and retry policy. */
@ConfigurationProperties("simplematch.quickfix-gateway.final-matching-events")
public record QuickFixGatewayFinalMatchingEventConsumerProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("matching.events") String topic,
    @DefaultValue("quickfix-final-matching-events") String consumerGroup,
    @DefaultValue("3") int maximumAttempts,
    @DefaultValue("100") int deliveryBatchSize,
    @DefaultValue("1000") long deliveryRetryDelayMillis,
    @DefaultValue(
            "Correct the Gateway durable delivery cause, then resume the same topic partition and"
                + " offset.")
        String recoveryInstructions) {
  /** Rejects incomplete critical-consumer configuration before the Gateway accepts traffic. */
  public QuickFixGatewayFinalMatchingEventConsumerProperties {
    requireText(topic, "final Matching Event topic");
    requireText(consumerGroup, "final Matching Event consumer group");
    requireText(recoveryInstructions, "final Matching Event recovery instructions");
    if (maximumAttempts <= 0 || deliveryBatchSize <= 0 || deliveryRetryDelayMillis <= 0) {
      throw new IllegalArgumentException(
          "Gateway final Matching Event retry settings must be positive");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}

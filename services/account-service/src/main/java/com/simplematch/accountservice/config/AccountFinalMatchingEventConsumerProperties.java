package com.simplematch.accountservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds Account's critical final Matching Event consumer policy. */
@ConfigurationProperties("simplematch.account-service.final-matching-events")
public record AccountFinalMatchingEventConsumerProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("matching.events") String topic,
    @DefaultValue("account-final-matching-events") String consumerGroup,
    @DefaultValue("3") int maximumAttempts,
    @DefaultValue("Correct Account Authority, then resume the same topic partition and offset.")
        String recoveryInstructions) {
  /** Rejects incomplete topic and bounded-retry configuration during startup. */
  public AccountFinalMatchingEventConsumerProperties {
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("Account final Matching Event topic is required");
    }
    if (consumerGroup == null || consumerGroup.isBlank()) {
      throw new IllegalArgumentException("Account final Matching Event consumer group is required");
    }
    if (maximumAttempts <= 0) {
      throw new IllegalArgumentException(
          "Account final Matching Event maximum attempts must be positive");
    }
    if (recoveryInstructions == null || recoveryInstructions.isBlank()) {
      throw new IllegalArgumentException(
          "Account final Matching Event recovery instructions are required");
    }
  }
}

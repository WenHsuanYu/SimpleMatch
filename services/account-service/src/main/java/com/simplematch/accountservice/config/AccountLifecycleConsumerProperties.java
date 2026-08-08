package com.simplematch.accountservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Critical Account Authority lifecycle-consumer settings. */
@ConfigurationProperties("simplematch.account-service.lifecycle-consumer")
public record AccountLifecycleConsumerProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("3") int maximumAttempts,
    @DefaultValue("Correct the account state, then resume the same topic partition and offset.")
        String recoveryInstructions) {
  /** Validates bounded retry and operator recovery settings. */
  public AccountLifecycleConsumerProperties {
    if (maximumAttempts <= 0) {
      throw new IllegalArgumentException("account lifecycle maximum attempts must be positive");
    }
    if (recoveryInstructions == null || recoveryInstructions.isBlank()) {
      throw new IllegalArgumentException("account lifecycle recovery instructions are required");
    }
  }
}

package com.simplematch.persistence.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds the final Matching Event critical-consumer policy for Persistence. */
@ConfigurationProperties("simplematch.persistence.matching-events")
public record PersistenceMatchingEventConsumerProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("matching.events") String topic,
    @DefaultValue("3") int maximumAttempts,
    @DefaultValue(
            "Correct the Persistence database, then resume the same topic partition and offset.")
        String recoveryInstructions) {
  /** Rejects non-deterministic retry and topic settings during service startup. */
  public PersistenceMatchingEventConsumerProperties {
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("Persistence matching-events topic is required");
    }
    if (maximumAttempts <= 0) {
      throw new IllegalArgumentException(
          "Persistence matching-events maximum attempts must be positive");
    }
    if (recoveryInstructions == null || recoveryInstructions.isBlank()) {
      throw new IllegalArgumentException(
          "Persistence matching-events recovery instructions are required");
    }
  }
}

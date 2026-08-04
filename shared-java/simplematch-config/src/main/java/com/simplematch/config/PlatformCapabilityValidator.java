package com.simplematch.config;

import java.util.Objects;
import org.springframework.core.env.ConfigurableEnvironment;

/** Validates independently bound shared capabilities at startup. */
public final class PlatformCapabilityValidator {
  private final ConfigurableEnvironment environment;
  private final EnvironmentConfigurationValidator environmentValidator;
  private final EnvironmentProperties environmentProperties;
  private final KafkaProperties kafkaProperties;
  private final ObservabilityProperties observabilityProperties;
  private final MarketProperties marketProperties;

  /**
   * Creates the capability validator over the settings that currently have cross-field rules.
   *
   * @param environment Spring's resolved environment
   * @param environmentValidator environment and credential rule validator
   * @param environmentProperties independently bound environment capability
   * @param kafkaProperties independently bound Kafka capability
   * @param observabilityProperties independently bound observability capability
   * @param marketProperties independently bound market capability
   */
  public PlatformCapabilityValidator(
      ConfigurableEnvironment environment,
      EnvironmentConfigurationValidator environmentValidator,
      EnvironmentProperties environmentProperties,
      KafkaProperties kafkaProperties,
      ObservabilityProperties observabilityProperties,
      MarketProperties marketProperties) {
    this.environment = Objects.requireNonNull(environment, "environment");
    this.environmentValidator =
        Objects.requireNonNull(environmentValidator, "environmentValidator");
    this.environmentProperties =
        Objects.requireNonNull(environmentProperties, "environmentProperties");
    this.kafkaProperties = Objects.requireNonNull(kafkaProperties, "kafkaProperties");
    this.observabilityProperties =
        Objects.requireNonNull(observabilityProperties, "observabilityProperties");
    this.marketProperties = Objects.requireNonNull(marketProperties, "marketProperties");
  }

  /** Runs the environment and capability-level rules. */
  public void validate() {
    environmentValidator.validate(environment, environmentProperties);
    PlatformSettingsValidator.validate(kafkaProperties);
    PlatformSettingsValidator.validate(observabilityProperties);
    PlatformSettingsValidator.validate(marketProperties);
  }
}

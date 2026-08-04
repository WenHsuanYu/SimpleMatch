package com.simplematch.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

/** Installs shared typed configuration and validates the resolved Spring Environment. */
@AutoConfiguration
@EnableConfigurationProperties({
  EnvironmentProperties.class,
  KafkaProperties.class,
  PostgresProperties.class,
  RedisProperties.class,
  GrpcProperties.class,
  ObservabilityProperties.class,
  MarketProperties.class
})
public final class SimpleMatchConfigurationAutoConfiguration {
  @Bean
  EnvironmentConfigurationValidator environmentConfigurationValidator() {
    return new EnvironmentConfigurationValidator();
  }

  @Bean
  PlatformCapabilityValidator platformCapabilityValidator(
      ConfigurableEnvironment environment,
      EnvironmentConfigurationValidator environmentValidator,
      EnvironmentProperties environmentProperties,
      KafkaProperties kafkaProperties,
      ObservabilityProperties observabilityProperties,
      MarketProperties marketProperties) {
    return new PlatformCapabilityValidator(
        environment,
        environmentValidator,
        environmentProperties,
        kafkaProperties,
        observabilityProperties,
        marketProperties);
  }

  @Bean
  SmartInitializingSingleton simpleMatchConfigurationStartupValidation(
      PlatformCapabilityValidator capabilityValidator) {
    return capabilityValidator::validate;
  }
}

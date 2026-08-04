package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independently bindable deployment-environment capability. */
@ConfigurationProperties("simplematch")
public record EnvironmentProperties(String environment) {
  /** Normalizes an absent or blank deployment environment to local development. */
  public EnvironmentProperties {
    environment = PlatformPropertyDefaults.string(environment, "local");
  }
}

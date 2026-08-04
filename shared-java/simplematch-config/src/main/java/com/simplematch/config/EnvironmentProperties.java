package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Independently bindable deployment-environment capability.
 *
 * @param environment logical deployment environment, such as {@code local}, {@code test},
 *     {@code staging}, or {@code production}
 */
@ConfigurationProperties("simplematch")
public record EnvironmentProperties(String environment) {
  /** Normalizes an absent or blank deployment environment to local development. */
  public EnvironmentProperties {
    environment = PlatformPropertyDefaults.string(environment, "local");
  }
}

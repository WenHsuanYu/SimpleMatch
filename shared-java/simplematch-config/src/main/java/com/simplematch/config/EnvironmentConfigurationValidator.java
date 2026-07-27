package com.simplematch.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/** Validates environment ownership and Kubernetes configuration boundaries during startup. */
public final class EnvironmentConfigurationValidator {
  private static final Set<String> ENVIRONMENT_PROFILES =
      Set.of("local", "test", "staging", "production");
  private static final String POSTGRES_DSN = "simplematch.postgres.dsn";

  /**
   * Validates profile exclusivity, profile-to-property agreement, and managed-environment inputs.
   *
   * @param environment Spring's resolved environment
   * @param configuredEnvironment the bound {@code simplematch.environment} value
   */
  public void validate(ConfigurableEnvironment environment, String configuredEnvironment) {
    final Set<String> activeEnvironmentProfiles = activeEnvironmentProfiles(environment);
    if (activeEnvironmentProfiles.size() != 1) {
      throw new IllegalStateException(
          "Exactly one SimpleMatch environment profile must be active: " + ENVIRONMENT_PROFILES);
    }

    final String activeEnvironment = activeEnvironmentProfiles.iterator().next();
    if (!activeEnvironment.equals(configuredEnvironment)) {
      throw new IllegalStateException(
          "simplematch.environment must match the active environment profile: " + activeEnvironment);
    }

    if ("staging".equals(activeEnvironment) || "production".equals(activeEnvironment)) {
      validateKubernetesInputs(environment);
    }
  }

  /** Validates all bound platform settings before service-owned beans are created. */
  public void validate(ConfigurableEnvironment environment, PlatformProperties properties) {
    validatePlatformProperties(properties);
    validate(environment, properties.environment());
  }

  private void validatePlatformProperties(PlatformProperties properties) {
    if (properties.kafka().partitions().ordersCommands() <= 0
        || properties.kafka().partitions().ordersValidated() <= 0
        || properties.kafka().partitions().matchingExecutions() <= 0) {
      throw new IllegalStateException("Kafka partition counts must be positive.");
    }
    if (properties.observability().prometheus().port() < 1
        || properties.observability().prometheus().port() > 65_535) {
      throw new IllegalStateException("Prometheus port must be between 1 and 65535.");
    }
    if (!"TWD".equals(properties.market().currency())) {
      throw new IllegalStateException("Phase-one market currency must be TWD.");
    }
    if (!"Asia/Taipei".equals(properties.market().timeZone())) {
      throw new IllegalStateException("Phase-one market time zone must be Asia/Taipei.");
    }
  }

  private Set<String> activeEnvironmentProfiles(ConfigurableEnvironment environment) {
    final String[] configuredProfiles = environment.getActiveProfiles().length == 0
        ? environment.getDefaultProfiles()
        : environment.getActiveProfiles();
    final Set<String> activeProfiles = new LinkedHashSet<>();
    Arrays.stream(configuredProfiles)
        .map(profile -> profile.toLowerCase(Locale.ROOT))
        .filter(ENVIRONMENT_PROFILES::contains)
        .forEach(activeProfiles::add);
    return activeProfiles;
  }

  private void validateKubernetesInputs(ConfigurableEnvironment environment) {
    final Set<String> configMapKeys = keysFrom(environment, "configmap");
    final Set<String> secretKeys = keysFrom(environment, "secret");
    if (configMapKeys.isEmpty()) {
      throw new IllegalStateException(
          "Staging and production require a Kubernetes ConfigMap property source.");
    }
    if (secretKeys.isEmpty()) {
      throw new IllegalStateException(
          "Staging and production require a Kubernetes Secret property source.");
    }

    final Set<String> conflictingKeys = new LinkedHashSet<>(configMapKeys);
    conflictingKeys.retainAll(secretKeys);
    if (!conflictingKeys.isEmpty()) {
      throw new IllegalStateException(
          "Kubernetes ConfigMap and Secret inputs must have disjoint key ownership: "
              + conflictingKeys);
    }
    if (!secretKeys.contains(POSTGRES_DSN)) {
      throw new IllegalStateException(
          "Staging and production require " + POSTGRES_DSN + " from a Kubernetes Secret.");
    }
  }

  private Set<String> keysFrom(ConfigurableEnvironment environment, String sourceKind) {
    final Set<String> keys = new LinkedHashSet<>();
    for (PropertySource<?> propertySource : environment.getPropertySources()) {
      if (!isKubernetesSource(propertySource, sourceKind)
          || !(propertySource instanceof EnumerablePropertySource<?> enumerableSource)) {
        continue;
      }
      for (String propertyName : enumerableSource.getPropertyNames()) {
        keys.add(canonicalKey(propertyName));
      }
    }
    return keys;
  }

  private boolean isKubernetesSource(PropertySource<?> propertySource, String sourceKind) {
    final String sourceName = propertySource.getName().toLowerCase(Locale.ROOT);
    final String sourceType = propertySource.getClass().getName().toLowerCase(Locale.ROOT);
    return (sourceName.contains("kubernetes") || sourceType.contains("kubernetes"))
        && (sourceName.contains(sourceKind) || sourceType.contains(sourceKind));
  }

  private String canonicalKey(String propertyName) {
    return propertyName.toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
  }
}

package com.simplematch.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.ConfigurableEnvironment;

/** Validates environment ownership and Kubernetes configuration boundaries during startup. */
public final class EnvironmentConfigurationValidator {
  private static final Set<String> ENVIRONMENT_PROFILES =
      Set.of("local", "test", "staging", "production");
  private static final KubernetesEnvironmentInputValidator KUBERNETES_INPUTS =
      new KubernetesEnvironmentInputValidator();

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
          "simplematch.environment must match the active environment profile: "
              + activeEnvironment);
    }

    if ("staging".equals(activeEnvironment) || "production".equals(activeEnvironment)) {
      KUBERNETES_INPUTS.validate(environment);
    }
  }

  /** Validates the independently bound environment capability. */
  public void validate(ConfigurableEnvironment environment, EnvironmentProperties properties) {
    validate(environment, properties.environment());
  }

  private Set<String> activeEnvironmentProfiles(ConfigurableEnvironment environment) {
    final String[] configuredProfiles =
        environment.getActiveProfiles().length == 0
            ? environment.getDefaultProfiles()
            : environment.getActiveProfiles();
    final Set<String> activeProfiles = new LinkedHashSet<>();
    Arrays.stream(configuredProfiles)
        .map(profile -> profile.toLowerCase(Locale.ROOT))
        .filter(ENVIRONMENT_PROFILES::contains)
        .forEach(activeProfiles::add);
    return activeProfiles;
  }
}

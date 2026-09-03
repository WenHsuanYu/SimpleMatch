package com.simplematch.config;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/** Validates Kubernetes-owned configuration and the secure PostgreSQL DSN contract. */
final class KubernetesEnvironmentInputValidator {
  private static final String POSTGRES_DSN = "simplematch.postgres.dsn";

  /** Validates ConfigMap/Secret ownership and the effective secure PostgreSQL DSN. */
  void validate(ConfigurableEnvironment environment) {
    final Set<String> configMapKeys = keysFrom(environment, "configmap");
    final Set<String> secretKeys = keysFrom(environment, "secret");
    requireKubernetesSources(configMapKeys, secretKeys);
    requireDisjointKubernetesOwnership(configMapKeys, secretKeys);
    validatePostgresSecret(environment, secretKeys);
  }

  private static void requireKubernetesSources(
      Set<String> configMapKeys, Set<String> secretKeys) {
    if (configMapKeys.isEmpty()) {
      throw new IllegalStateException(
          "Staging and production require a Kubernetes ConfigMap property source.");
    }
    if (secretKeys.isEmpty()) {
      throw new IllegalStateException(
          "Staging and production require a Kubernetes Secret property source.");
    }
  }

  private static void requireDisjointKubernetesOwnership(
      Set<String> configMapKeys, Set<String> secretKeys) {
    final Set<String> conflictingKeys = new LinkedHashSet<>(configMapKeys);
    conflictingKeys.retainAll(secretKeys);
    if (!conflictingKeys.isEmpty()) {
      throw new IllegalStateException(
          "Kubernetes ConfigMap and Secret inputs must have disjoint key ownership: "
              + conflictingKeys);
    }
  }

  private static void validatePostgresSecret(
      ConfigurableEnvironment environment, Set<String> secretKeys) {
    requirePostgresDsnKey(secretKeys);
    final String secretDsn = postgresDsnFromSecret(environment);
    final String effectiveDsn = environment.getProperty(POSTGRES_DSN);
    if (secretDsn == null || secretDsn.isBlank()) {
      throw new IllegalStateException(
          "Staging and production require a non-blank "
              + POSTGRES_DSN
              + " from a Kubernetes Secret.");
    }
    if (effectiveDsn == null || effectiveDsn.isBlank()) {
      throw new IllegalStateException(
          "Staging and production require the effective " + POSTGRES_DSN + " property.");
    }
    if (!Objects.equals(effectiveDsn, secretDsn)) {
      throw new IllegalStateException(
          "The effective " + POSTGRES_DSN + " must exactly match the Kubernetes Secret value.");
    }
    requireSecurePostgresDsn(effectiveDsn);
  }

  private static void requirePostgresDsnKey(Set<String> secretKeys) {
    if (!secretKeys.contains(POSTGRES_DSN)) {
      throw new IllegalStateException(
          "Staging and production require " + POSTGRES_DSN + " from a Kubernetes Secret.");
    }
  }

  private static void requireSecurePostgresDsn(String dsn) {
    try {
      PostgresJdbcUrl.parseSecure(dsn);
    } catch (IllegalStateException failure) {
      throw new IllegalStateException(
          "Staging and production require a PostgreSQL DSN with "
              + "sslmode=verify-full and sslrootcert.",
          failure);
    }
  }

  private static String postgresDsnFromSecret(ConfigurableEnvironment environment) {
    for (PropertySource<?> propertySource : environment.getPropertySources()) {
      if (!isKubernetesSource(propertySource, "secret")
          || !(propertySource instanceof EnumerablePropertySource<?> enumerableSource)) {
        continue;
      }
      for (String propertyName : enumerableSource.getPropertyNames()) {
        if (POSTGRES_DSN.equals(canonicalKey(propertyName))) {
          final Object value = propertySource.getProperty(propertyName);
          if (value != null) {
            return value.toString();
          }
        }
      }
    }
    return null;
  }

  private static Set<String> keysFrom(ConfigurableEnvironment environment, String sourceKind) {
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

  private static boolean isKubernetesSource(PropertySource<?> propertySource, String sourceKind) {
    final String sourceName = propertySource.getName().toLowerCase(Locale.ROOT);
    final String sourceType = propertySource.getClass().getName().toLowerCase(Locale.ROOT);
    return (sourceName.contains("kubernetes") || sourceType.contains("kubernetes"))
        && (sourceName.contains(sourceKind) || sourceType.contains(sourceKind));
  }

  private static String canonicalKey(String propertyName) {
    final String canonical =
        propertyName.toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
    return "postgres.dsn".equals(canonical) ? POSTGRES_DSN : canonical;
  }
}

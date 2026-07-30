package com.simplematch.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class EnvironmentConfigurationValidatorTest {
  private final EnvironmentConfigurationValidator validator =
      new EnvironmentConfigurationValidator();

  @Test
  void rejectsMultipleEnvironmentProfiles() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("local", "test");

    assertThatThrownBy(() -> validator.validate(environment, "local"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Exactly one");
  }

  @Test
  void rejectsEnvironmentValueThatDoesNotMatchTheActiveProfile() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");

    assertThatThrownBy(() -> validator.validate(environment, "local"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must match");
  }

  @Test
  void stagingRequiresConfigMapAndSecretInputs() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("staging");
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "kubernetes-configmap: simplematch-platform-config",
                Map.of("simplematch.kafka.brokers", "kafka:9092")));

    assertThatThrownBy(() -> validator.validate(environment, "staging"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Kubernetes Secret");
  }

  @Test
  void rejectsConfigMapAndSecretKeyConflicts() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("staging");
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "kubernetes-configmap: simplematch-platform-config",
                Map.of("simplematch.kafka.brokers", "kafka:9092")));
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "kubernetes-secrets: simplematch-runtime-secrets",
                Map.of(
                    "simplematch.kafka.brokers", "secret-kafka:9092",
                    "simplematch.postgres.dsn", "jdbc:postgresql://postgres/simplematch")));

    assertThatThrownBy(() -> validator.validate(environment, "staging"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("disjoint key ownership");
  }

  @Test
  void acceptsDisjointStagingSourcesWithDatabaseDsnFromSecret() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("staging");
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "kubernetes-configmap: simplematch-platform-config",
                Map.of("simplematch.kafka.brokers", "kafka:9092")));
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "kubernetes-secrets: simplematch-runtime-secrets",
                Map.of("simplematch.postgres.dsn", "jdbc:postgresql://postgres/simplematch")));

    validator.validate(environment, "staging");
  }

  @Test
  void recognizesKubernetesPropertySourcesByTypeWhenTheirNamesAreOpaque() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("staging");
    environment
        .getPropertySources()
        .addFirst(
            new KubernetesConfigMapSource(
                "applicationConfig: [classpath:/application-staging.yaml]",
                Map.of("simplematch.kafka.brokers", "kafka:9092")));
    environment
        .getPropertySources()
        .addFirst(
            new KubernetesSecretSource(
                "applicationConfig: [classpath:/application-secrets.yaml]",
                Map.of("simplematch.postgres.dsn", "jdbc:postgresql://postgres/simplematch")));

    validator.validate(environment, "staging");
  }

  @Test
  void rejectsInvalidTaiwanMarketDefaults() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("local");
    final PlatformProperties properties =
        new PlatformProperties(
            "local",
            null,
            null,
            null,
            null,
            null,
            null,
            new PlatformProperties.MarketProperties("USD", "America/New_York"));

    assertThatThrownBy(() -> validator.validate(environment, properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TWD");
  }

  private static final class KubernetesConfigMapSource extends MapPropertySource {
    private KubernetesConfigMapSource(String name, Map<String, Object> source) {
      super(name, source);
    }
  }

  private static final class KubernetesSecretSource extends MapPropertySource {
    private KubernetesSecretSource(String name, Map<String, Object> source) {
      super(name, source);
    }
  }
}

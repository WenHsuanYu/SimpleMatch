package com.simplematch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class CapabilityPropertiesPrecedenceTest {
  private static final String SECURE_POSTGRES_DSN =
      "jdbc:postgresql://staging/simplematch?sslmode=verify-full&sslrootcert=/etc/simplematch/postgres-tls/ca.crt";

  @Test
  void profileYamlOverridesBaseYaml() {
    try (ConfigurableApplicationContext context = start(Map.of())) {
      assertThat(context.getBean(EnvironmentProperties.class).environment()).isEqualTo("test");
      assertThat(context.getBean(KafkaProperties.class).brokers())
          .isEqualTo("profile-broker:9092");
    }
  }

  @Test
  void environmentVariablesOverrideProfileYaml() {
    try (ConfigurableApplicationContext context =
        start(Map.of("SIMPLEMATCH_KAFKA_BROKERS", "environment-broker:9092"))) {
      assertThat(context.getBean(KafkaProperties.class).brokers())
          .isEqualTo("environment-broker:9092");
    }
  }

  @Test
  void testOnlyCommandLineOverrideHasHighestPrecedence() {
    try (ConfigurableApplicationContext context =
        start(
            Map.of("SIMPLEMATCH_KAFKA_BROKERS", "environment-broker:9092"),
            "--simplematch.kafka.brokers=test-override-broker:9092")) {
      assertThat(context.getBean(KafkaProperties.class).brokers())
          .isEqualTo("test-override-broker:9092");
    }
  }

  @Test
  void capabilityPropertiesBindIndependently() {
    try (ConfigurableApplicationContext context =
        start(Map.of("SIMPLEMATCH_KAFKA_BROKERS", "environment-broker:9092"))) {
      assertThat(context.getBean(EnvironmentProperties.class).environment()).isEqualTo("test");
      assertThat(context.getBean(KafkaProperties.class).brokers())
          .isEqualTo("environment-broker:9092");
      assertThat(context.getBean(KafkaProperties.class).topics().ordersValidated())
          .isEqualTo("orders.validated");
      assertThat(context.getBean(PostgresProperties.class).dsn())
          .isEqualTo("jdbc:postgresql://localhost:5432/simplematch");
      assertThat(context.getBean(MarketProperties.class).currency()).isEqualTo("TWD");
    }
  }

  @Test
  void legacyJsonDiscoveryDoesNotParticipateInSpringConfiguration() {
    try (ConfigurableApplicationContext context =
        start(Map.of("SIMPLEMATCH_CONFIG", "/does-not-exist/simplematch.json"))) {
      assertThat(context.getBean(EnvironmentProperties.class).environment()).isEqualTo("test");
    }
  }

  @Test
  void canonicalPropertiesBindInEveryEnvironmentProfile() {
    try (ConfigurableApplicationContext local = start(Map.of(), "local", Map.of(), Map.of());
        ConfigurableApplicationContext test = start(Map.of(), "test", Map.of(), Map.of());
        ConfigurableApplicationContext staging =
            start(
                Map.of(),
                "staging",
                Map.of("simplematch.kafka.brokers", "staging-kafka:9092"),
                Map.of("simplematch.postgres.dsn", SECURE_POSTGRES_DSN));
        ConfigurableApplicationContext production =
            start(
                Map.of(),
                "production",
                Map.of("simplematch.kafka.brokers", "production-kafka:9092"),
                Map.of(
                    "simplematch.postgres.dsn",
                    SECURE_POSTGRES_DSN.replace("staging", "production")))) {
      assertThat(local.getBean(EnvironmentProperties.class).environment()).isEqualTo("local");
      assertThat(test.getBean(EnvironmentProperties.class).environment()).isEqualTo("test");
      assertThat(staging.getBean(EnvironmentProperties.class).environment())
          .isEqualTo("staging");
      assertThat(production.getBean(EnvironmentProperties.class).environment())
          .isEqualTo("production");
    }
  }

  private ConfigurableApplicationContext start(
      Map<String, Object> systemEnvironment, String... arguments) {
    return start(systemEnvironment, "test", Map.of(), Map.of(), arguments);
  }

  private ConfigurableApplicationContext start(
      Map<String, Object> systemEnvironment,
      String profile,
      Map<String, Object> configMapProperties,
      Map<String, Object> secretProperties,
      String... arguments) {
    final StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new MapPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, systemEnvironment));
    if (!configMapProperties.isEmpty()) {
      environment
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "kubernetes-configmap: simplematch-platform-config", configMapProperties));
    }
    if (!secretProperties.isEmpty()) {
      environment
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "kubernetes-secrets: simplematch-runtime-secrets", secretProperties));
    }

    final SpringApplication application = new SpringApplication(ConfigurationFixture.class);
    application.setEnvironment(environment);
    application.setDefaultProperties(
        Map.of(
            "spring.config.additional-location", "classpath:/configuration-precedence/",
            "spring.profiles.active", profile,
            "spring.main.web-application-type", "none"));
    return application.run(arguments);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    EnvironmentProperties.class,
    KafkaProperties.class,
    PostgresProperties.class,
    RedisProperties.class,
    GrpcProperties.class,
    ObservabilityProperties.class,
    MarketProperties.class
  })
  static class ConfigurationFixture {}
}

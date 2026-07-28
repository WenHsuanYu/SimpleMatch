package com.simplematch.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformPropertiesPrecedenceTest {
    @Test
    void profileYamlOverridesBaseYaml() {
        try (ConfigurableApplicationContext context = start(Map.of())) {
            assertThat(context.getBean(PlatformProperties.class).environment()).isEqualTo("test");
            assertThat(context.getBean(PlatformProperties.class).kafka().brokers())
                    .isEqualTo("profile-broker:9092");
        }
    }

    @Test
    void environmentVariablesOverrideProfileYaml() {
        try (ConfigurableApplicationContext context = start(Map.of(
                "SIMPLEMATCH_KAFKA_BROKERS", "environment-broker:9092"))) {
            assertThat(context.getBean(PlatformProperties.class).kafka().brokers())
                    .isEqualTo("environment-broker:9092");
        }
    }

    @Test
    void testOnlyCommandLineOverrideHasHighestPrecedence() {
        try (ConfigurableApplicationContext context = start(
                Map.of("SIMPLEMATCH_KAFKA_BROKERS", "environment-broker:9092"),
                "--simplematch.kafka.brokers=test-override-broker:9092")) {
            assertThat(context.getBean(PlatformProperties.class).kafka().brokers())
                    .isEqualTo("test-override-broker:9092");
        }
    }

    @Test
    void legacyJsonDiscoveryDoesNotParticipateInSpringConfiguration() {
        try (ConfigurableApplicationContext context = start(Map.of(
                "SIMPLEMATCH_CONFIG", "/does-not-exist/simplematch.json"))) {
            assertThat(context.getBean(PlatformProperties.class).environment()).isEqualTo("test");
        }
    }

    @Test
    void canonicalPropertiesBindInEveryEnvironmentProfile() {
        try (ConfigurableApplicationContext local = start(Map.of(), "local", Map.of(), Map.of());
             ConfigurableApplicationContext test = start(Map.of(), "test", Map.of(), Map.of());
             ConfigurableApplicationContext staging = start(
                     Map.of(),
                     "staging",
                     Map.of("simplematch.kafka.brokers", "staging-kafka:9092"),
                     Map.of("simplematch.postgres.dsn", "jdbc:postgresql://staging/simplematch"));
             ConfigurableApplicationContext production = start(
                     Map.of(),
                     "production",
                     Map.of("simplematch.kafka.brokers", "production-kafka:9092"),
                     Map.of("simplematch.postgres.dsn", "jdbc:postgresql://production/simplematch"))) {
            assertThat(local.getBean(PlatformProperties.class).environment()).isEqualTo("local");
            assertThat(test.getBean(PlatformProperties.class).environment()).isEqualTo("test");
            assertThat(staging.getBean(PlatformProperties.class).environment()).isEqualTo("staging");
            assertThat(production.getBean(PlatformProperties.class).environment()).isEqualTo("production");
        }
    }

    private ConfigurableApplicationContext start(Map<String, Object> systemEnvironment, String... arguments) {
        return start(systemEnvironment, "test", Map.of(), Map.of(), arguments);
    }

    private ConfigurableApplicationContext start(
            Map<String, Object> systemEnvironment,
            String profile,
            Map<String, Object> configMapProperties,
            Map<String, Object> secretProperties,
            String... arguments) {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, systemEnvironment));
        if (!configMapProperties.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "kubernetes-configmap: simplematch-platform-config", configMapProperties));
        }
        if (!secretProperties.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "kubernetes-secrets: simplematch-runtime-secrets", secretProperties));
        }

        final SpringApplication application = new SpringApplication(ConfigurationFixture.class);
        application.setEnvironment(environment);
        application.setDefaultProperties(Map.of(
                "spring.config.additional-location", "classpath:/configuration-precedence/",
                "spring.profiles.active", profile,
                "spring.main.web-application-type", "none"));
        return application.run(arguments);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PlatformProperties.class)
    static class ConfigurationFixture {
    }
}

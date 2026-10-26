package com.simplematch.queryservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.kubernetes.commons.config.SourceDataEntriesProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Verifies the installed framework's multi-key Secret file convention before Lab deployment. */
class NamedSecuritySecretContractTest {
  @ParameterizedTest
  @ValueSource(strings = {"query-service", "quickfix-gateway"})
  void namedPolicyFileIsLoadedAlongsideDatabaseSettings(String applicationName) {
    final StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource(
        "application", Map.of("spring.application.name", applicationName)));
    environment.setActiveProfiles("production");
    final Map<String, String> secret = Map.of(
        "postgres_dsn", "synthetic-database-setting",
        applicationName + ".properties", "simplematch.fixture-check=loaded\n");

    assertThat(SourceDataEntriesProcessor.processAllEntries(secret, environment, true))
        .containsEntry("postgres_dsn", "synthetic-database-setting")
        .containsEntry("simplematch.fixture-check", "loaded");
  }
}

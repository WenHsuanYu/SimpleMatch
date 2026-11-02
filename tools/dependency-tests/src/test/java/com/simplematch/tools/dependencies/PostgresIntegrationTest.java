package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies configured service migrations and core constraints against disposable PostgreSQL. */
class PostgresIntegrationTest {
  @TempDir private Path directory;

  @AutoClose
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4")
          .withLabel("simplematch.test", "s2-postgres")
          .withStartupTimeout(Duration.ofSeconds(90));

  @Test
  void configuredOwnersMigrateValidateAndEnforceConstraints() throws Exception {
    final var owners = FlywayOwners.configurations();
    assertTrue(!owners.isEmpty(), "No Flyway owners were configured");
    POSTGRES.start();
    final DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUsername(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    try (var connection = source.getConnection()) {
      FlywayTaskProbe.run(
          POSTGRES, directory, "flywayMigrateAll", "flywayValidateAll");
      for (String owner : owners.keySet()) {
        assertTrue(FlywayOwners.connect(owner, source).info().applied().length > 0, owner);
      }
      PostgresContracts.constraints(connection);
    }
  }
}

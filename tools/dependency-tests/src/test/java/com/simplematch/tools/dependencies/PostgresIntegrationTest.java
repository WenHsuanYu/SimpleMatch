package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies the configured six-owner migration contract against one disposable database. */
class PostgresIntegrationTest {
  @TempDir private Path directory;

  @AutoClose
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4")
          .withLabel("simplematch.test", "s2-postgres")
          .withStartupTimeout(Duration.ofSeconds(90));

  @Test
  void allConfiguredOwnersMigrateRepeatValidateAndKeepSeparateHistories() throws Exception {
    final var owners = FlywayOwners.configurations();
    assertEquals(PostgresContracts.required().size(), owners.size());
    POSTGRES.start();
    final DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUsername(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    try (var connection = source.getConnection()) {
      assertTrue(
          PostgresContracts.bool(connection, "SELECT current_setting('server_encoding') = 'UTF8'"));
      for (String owner : owners.keySet()) {
        assertEquals(0, FlywayOwners.connect(owner, source).info().applied().length);
      }
      FlywayTaskProbe.run(
          POSTGRES, directory, "flywayInfoAll", "flywayMigrateAll", "flywayValidateAll");
      final var applied = new HashMap<String, Integer>();
      for (String owner : owners.keySet()) {
        final int count = FlywayOwners.connect(owner, source).info().applied().length;
        assertTrue(count > 0, owner);
        applied.put(owner, count);
      }
      FlywayTaskProbe.run(POSTGRES, directory, "flywayMigrateAll", "flywayValidateAll");
      for (String owner : owners.keySet()) {
        assertEquals(
            applied.get(owner).intValue(),
            FlywayOwners.connect(owner, source).info().applied().length);
        PostgresContracts.tables(connection, owner);
        PostgresContracts.plans(connection, owner);
      }
      PostgresContracts.constraints(connection);
      PostgresContracts.gatewayIdentity(connection);
    }
  }
}

package com.simplematch.persistence.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PersistenceFlywayMigrationTest {
  private static final String SCHEMA_NAME = "PERSISTENCE";

  @DisplayName("Flyway migration creates the persistence projection tables")
  @Test
  void migrateCreatesProjectionTables() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS persistence\\;SET SCHEMA persistence");
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

    Flyway.configure()
        .baselineOnMigrate(true)
        .baselineVersion("1")
        .dataSource(dataSource)
        .locations("classpath:db/migration/persistence")
        .load()
        .migrate();

    assertThat(hasTable(jdbcTemplate, "ORDERS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "EXECUTIONS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "PROCESSED_EVENTS")).isTrue();

    assertThat(hasColumn(jdbcTemplate, "ORDERS", "SOURCE_SESSION_ID")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "ORDERS", "CLIENT_ORDER_ID")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "EXECUTIONS", "FILL_QTY")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "PROCESSED_EVENTS", "CONSUMER_NAME")).isTrue();
  }

  private boolean hasTable(JdbcTemplate jdbcTemplate, String tableName) {
    return jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM INFORMATION_SCHEMA.TABLES
            WHERE UPPER(TABLE_SCHEMA) = ?
              AND UPPER(TABLE_NAME) = ?
            """,
            Integer.class,
            SCHEMA_NAME,
            tableName)
        > 0;
  }

  private boolean hasColumn(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
    return jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE UPPER(TABLE_SCHEMA) = ?
              AND UPPER(TABLE_NAME) = ?
              AND UPPER(COLUMN_NAME) = ?
            """,
            Integer.class,
            SCHEMA_NAME,
            tableName,
            columnName)
        > 0;
  }
}
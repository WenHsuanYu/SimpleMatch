package com.simplematch.accountservice.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AccountServiceFlywayMigrationTest {
  private static final String SCHEMA_NAME = "ACCOUNT_SERVICE";

  @DisplayName("Flyway migration creates the account-service authority tables")
  @Test
  void migrateCreatesAuthorityTables() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS account_service\\;SET SCHEMA account_service");
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

    Flyway.configure()
        .baselineOnMigrate(true)
        .baselineVersion("1")
        .dataSource(dataSource)
        .locations("classpath:db/migration/account-service")
        .load()
        .migrate();

    assertThat(hasTable(jdbcTemplate, "ACCOUNT_LIMITS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "ACCOUNT_POSITIONS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "ACCOUNT_RESERVATIONS")).isTrue();

    assertThat(hasColumn(jdbcTemplate, "ACCOUNT_LIMITS", "AVAILABLE_NOTIONAL")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "ACCOUNT_POSITIONS", "SYMBOL")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "ACCOUNT_RESERVATIONS", "RESERVATION_ID")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "ACCOUNT_RESERVATIONS", "STATUS")).isTrue();
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
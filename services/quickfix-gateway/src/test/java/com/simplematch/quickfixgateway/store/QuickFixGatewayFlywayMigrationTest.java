package com.simplematch.quickfixgateway.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Verifies a clean Gateway database receives its durable final-event and QuickFIX/J schema. */
class QuickFixGatewayFlywayMigrationTest {
  private static final String SCHEMA_NAME = "QUICKFIX_GATEWAY";

  @Test
  void migrateEmptyDatabaseCreatesDeliveryLedgerQuickFixAndOperationAuditTables() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());

    assertThat(hasTable(jdbcTemplate, "MATCHING_EVENT_INBOX")).isTrue();
    assertThat(hasTable(jdbcTemplate, "FIX_DELIVERY_INTENTS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "MATCHING_CONSUMER_PROGRESS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "MATCHING_CONSUMER_QUARANTINES")).isTrue();
    assertThat(hasTable(jdbcTemplate, "SESSIONS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "MESSAGES")).isTrue();
    assertThat(hasTable(jdbcTemplate, "EVENT_LOG")).isTrue();
    assertThat(hasTable(jdbcTemplate, "MESSAGES_LOG")).isTrue();
    assertThat(hasTable(jdbcTemplate, "GATEWAY_OPERATION_AUDIT")).isTrue();
    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(2);
  }

  @Test
  void aSecondGatewayMigrationIsANoOp() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());
    final int migrationsAfterFirstRun = appliedMigrationCount(jdbcTemplate);
    migrate(jdbcTemplate.getDataSource());

    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(migrationsAfterFirstRun);
  }

  private DriverManagerDataSource newDataSource() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:"
            + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    return dataSource;
  }

  private void migrate(javax.sql.DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/quickfix-gateway")
        .load()
        .migrate();
  }

  private int appliedMigrationCount(JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM "flyway_schema_history"
        WHERE "success" AND "version" IS NOT NULL
        """,
        Integer.class);
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
}

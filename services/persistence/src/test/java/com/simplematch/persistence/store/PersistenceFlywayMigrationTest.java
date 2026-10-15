package com.simplematch.persistence.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PersistenceFlywayMigrationTest {
  private static final String SCHEMA_NAME = "PERSISTENCE";

  @DisplayName("an empty database receives the active Matching-event schema without obsolete projections")
  @Test
  void migrateEmptyDatabaseCreatesProjectionAndInboxTables() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());

    assertThat(hasTable(jdbcTemplate, "ORDERS")).isFalse();
    assertThat(hasTable(jdbcTemplate, "EXECUTIONS")).isFalse();
    assertThat(hasTable(jdbcTemplate, "INBOX")).isFalse();
    assertThat(hasTable(jdbcTemplate, "MATCHING_EVENT_INBOX")).isTrue();
    assertThat(hasTable(jdbcTemplate, "TRADES")).isTrue();
    assertThat(hasTable(jdbcTemplate, "ORDER_FILLS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "MATCHING_ORDER_PROJECTIONS")).isTrue();
    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(4);
  }

  @DisplayName("a second persistence migration is a no-op")
  @Test
  void repeatedMigrateIsNoOp() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());
    final int migrationsAfterFirstRun = appliedMigrationCount(jdbcTemplate);
    migrate(jdbcTemplate.getDataSource());

    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(migrationsAfterFirstRun);
  }

  @DisplayName("active trade facts accept valid rows and reject a zero quantity")
  @Test
  void constraintsRejectInvalidBusinessValues() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());
    jdbcTemplate.update(
        """
        INSERT INTO persistence.trades (
          trade_id, event_id, trading_day, trading_session_id, partition_id, source_input_offset,
          venue_mic, symbol, quantity_shares, price_units
        ) VALUES (?, ?, DATE '2026-10-15', 'CONTINUOUS', 0, 1, 'XTAI', '2330', 100, 10000)
        """, new byte[32], new byte[32]);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM persistence.trades", Integer.class))
        .isEqualTo(1);
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE persistence.trades SET quantity_shares = 0"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @DisplayName("quarantine status and recovery timestamp must describe the same lifecycle")
  @Test
  void constraintsRejectInconsistentQuarantineRecovery() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());
    jdbcTemplate.update(
        """
        INSERT INTO persistence.matching_consumer_quarantines (
          consumer_name, topic, partition_id, offset_value, payload_sha256, reason,
          status, quarantined_at_unix_ms
        ) VALUES ('persistence', 'matching.events.v1', 0, 1, ?, 'invalid event', 'QUARANTINED', 10)
        """, new byte[32]);
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE persistence.matching_consumer_quarantines SET status = 'RECOVERED'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE persistence.matching_consumer_quarantines SET recovered_at_unix_ms = 11"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbcTemplate.update("UPDATE persistence.matching_consumer_quarantines SET status = 'RECOVERED', recovered_at_unix_ms = 11");
  }

  private DriverManagerDataSource newDataSource() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    return dataSource;
  }

  private void migrate(javax.sql.DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/persistence")
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

package com.simplematch.marketdatapublisher.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MarketdataPublisherFlywayMigrationTest {
  @DisplayName("an empty database receives snapshot and outbox tables from one marketdata-publisher V1 migration")
  @Test
  void migratesEmptyDatabase() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

    migrate(jdbcTemplate);

    assertThat(tableExists(jdbcTemplate, "MARKET_SNAPSHOTS")).isTrue();
    assertThat(tableExists(jdbcTemplate, "OUTBOX")).isTrue();
    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(1);
  }

  @DisplayName("marketdata-publisher migration is idempotent and prevents two active snapshots for one day")
  @Test
  void migrationIsNoOpAndActiveSnapshotConstraintIsEnforced() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

    migrate(jdbcTemplate);
    migrate(jdbcTemplate);

    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(1);
    insertActiveSnapshot(jdbcTemplate, "018a1f7d-1a55-7000-8000-000000000001", "source-a", "a");
    assertThatThrownBy(() -> insertActiveSnapshot(
        jdbcTemplate, "018a1f7d-1a55-7000-8000-000000000002", "source-b", "b"))
        .isInstanceOf(RuntimeException.class);
  }

  private DriverManagerDataSource dataSource() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    return dataSource;
  }

  private void migrate(JdbcTemplate jdbcTemplate) {
    Flyway.configure()
        .dataSource(jdbcTemplate.getDataSource())
        .locations("classpath:db/migration/marketdata-publisher")
        .load()
        .migrate();
  }

  private int appliedMigrationCount(JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" AND \"version\" IS NOT NULL",
        Integer.class);
  }

  private boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
    return jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
        WHERE UPPER(TABLE_SCHEMA) = 'MARKETDATA_PUBLISHER' AND UPPER(TABLE_NAME) = ?
        """,
        Integer.class,
        tableName) > 0;
  }

  private void insertActiveSnapshot(JdbcTemplate jdbcTemplate, String snapshotId, String source, String checksumSeed) {
    jdbcTemplate.update(
        """
        INSERT INTO marketdata_publisher.market_snapshots (
          snapshot_id, trading_day, version, source_identity, source_timestamp_unix_ms, checksum,
          snapshot_payload, active, active_trading_day, published_at_unix_ms
        ) VALUES (?, DATE '2026-07-27', ?, ?, 1, ?, X'01', TRUE, DATE '2026-07-27', 1)
        """,
        UUID.fromString(snapshotId),
        snapshotId.endsWith("1") ? 1L : 2L,
        source,
        checksumSeed.repeat(64));
  }
}

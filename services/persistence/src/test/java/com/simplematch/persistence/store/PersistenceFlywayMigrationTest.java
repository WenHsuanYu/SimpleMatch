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

  @DisplayName("an empty database receives the complete persistence V1 schema")
  @Test
  void migrateEmptyDatabaseCreatesProjectionAndInboxTables() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());

    assertThat(hasTable(jdbcTemplate, "ORDERS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "EXECUTIONS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "INBOX")).isTrue();
    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(2);
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

  @DisplayName("projection constraints reject invalid quantities")
  @Test
  void constraintsRejectInvalidBusinessValues() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                                        INSERT INTO persistence.orders (
                                          order_id, account_id, symbol, shard_id, side, order_type, tif, qty, status,
                                          state_version, sender_comp_id, target_comp_id, cl_ord_id, created_at_unix_ms,
                                          updated_at_unix_ms
                                        ) VALUES ('order-1', 'account-1', '2330', 0, 'SIDE_BUY', 'ORDER_TYPE_LIMIT',
                                          'TIME_IN_FORCE_ROD', 0, 'NEW', 0, 'CLIENT', 'SIMPLEMATCH', 'clord-1', 1, 1)
                                        """))
        .isInstanceOf(RuntimeException.class);
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

package com.simplematch.accountservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AccountServiceFlywayMigrationTest {
  private static final String SCHEMA_NAME = "ACCOUNT_SERVICE";

  @DisplayName("an empty database receives the complete account-service authority schema")
  @Test
  void migrateEmptyDatabaseCreatesAuthorityTables() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());

    assertThat(hasTable(jdbcTemplate, "ACCOUNT_LIMITS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "ACCOUNT_POSITIONS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "ACCOUNT_RESERVATIONS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "OUTBOX")).isTrue();
    assertThat(hasTable(jdbcTemplate, "CONSUMER_QUARANTINES")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "OUTBOX", "CREATED_AT")).isTrue();
    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(5);
  }

  @DisplayName("a second account-service migration is a no-op")
  @Test
  void repeatedMigrateIsNoOp() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());
    final int migrationsAfterFirstRun = appliedMigrationCount(jdbcTemplate);
    migrate(jdbcTemplate.getDataSource());

    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(migrationsAfterFirstRun);
  }

  @DisplayName("account reservation constraints allow a supported side")
  @Test
  void constraintsAllowSupportedReservationSide() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());

    final int insertedRows =
        jdbcTemplate.update(
            """
                                INSERT INTO account_service.account_reservations (
                                  reservation_id, request_id, order_id, account_id, symbol, side, quantity,
                                  reserved_notional, status, created_at_unix_ms, updated_at_unix_ms,
                                  remaining_quantity, filled_quantity, version
                                ) VALUES ('reservation-valid', 'request-valid', 'order-valid', 'account-1', '2330',
                                  'SIDE_BUY', 1, 0, 'RESERVATION_STATUS_ACCEPTED', 1, 1, 1, 0, 0)
                                """);

    assertThat(insertedRows).isEqualTo(1);
  }

  @DisplayName("account reservation constraints reject an unsupported side")
  @Test
  void constraintsRejectUnsupportedReservationSide() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                                        INSERT INTO account_service.account_reservations (
                                          reservation_id, request_id, order_id, account_id, symbol, side, quantity,
                                          reserved_notional, status, created_at_unix_ms, updated_at_unix_ms
                                        ) VALUES ('reservation-1', 'request-1', 'order-1', 'account-1', '2330',
                                          'SIDE_UNSPECIFIED', 1, 0, 'RESERVATION_STATUS_ACCEPTED', 1, 1)
                                        """))
        .isInstanceOf(RuntimeException.class);
  }

  @DisplayName("account reservation constraints reject a non-positive quantity")
  @Test
  void constraintsRejectNonPositiveReservationQuantity() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                                        INSERT INTO account_service.account_reservations (
                                          reservation_id, request_id, order_id, account_id, symbol, side, quantity,
                                          reserved_notional, status, created_at_unix_ms, updated_at_unix_ms
                                        ) VALUES ('reservation-2', 'request-2', 'order-2', 'account-1', '2330',
                                          'SIDE_BUY', 0, 0, 'RESERVATION_STATUS_ACCEPTED', 1, 1)
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
        .locations("classpath:db/migration/account-service")
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

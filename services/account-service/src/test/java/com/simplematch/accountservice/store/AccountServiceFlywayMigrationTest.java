package com.simplematch.accountservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AccountServiceFlywayMigrationTest {
  private static final String SCHEMA_NAME = "ACCOUNT_SERVICE";
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

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
    assertThat(hasTable(jdbcTemplate, "MATCHING_EVENT_INBOX")).isTrue();
    assertThat(hasTable(jdbcTemplate, "MATCHING_EVENT_CONSUMER_PROGRESS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "MATCHING_EVENT_CONSUMER_QUARANTINES")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "OUTBOX", "CREATED_AT")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "ACCOUNT_RESERVATIONS", "TRADING_DAY")).isTrue();
    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(12);
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

  @DisplayName("account reservation constraints accept a complete current-contract row")
  @Test
  void constraintsAllowSupportedReservationSide() {
    final JdbcTemplate jdbcTemplate = migratedDatabase();

    assertThat(insertValidReservation(jdbcTemplate)).isEqualTo(1);
    assertThat(hasColumn(jdbcTemplate, "ACCOUNT_LIMITS", "SCOPE_TYPE")).isFalse();
    assertThat(hasColumn(jdbcTemplate, "ACCOUNT_LIMITS", "SCOPE_KEY")).isFalse();
    assertThat(jdbcTemplate.queryForObject(
            "SELECT request_id FROM account_service.account_reservations", UUID.class))
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000011"));
  }

  @DisplayName("reservation constraints reject one invalid fact at a time")
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "side = 'SIDE_UNSPECIFIED'",
      "quantity = 0",
      "venue_mic = 'XXXX'",
      "symbol = 'lowercase'",
      "trading_day = NULL",
      "request_id = 'not-a-uuid'",
      "limit_price = 0",
      "reserved_notional = 922337203685477.5808",
      "limit_price = 922337203685477.5808",
      "quantity = 2, remaining_quantity = 2, limit_price = 922337203685477.5807",
      "filled_quantity = 1",
      "status = 'RESERVATION_STATUS_APPLIED'"
  })
  void rejectsInvalidReservationFact(String assignment) {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    assertThat(insertValidReservation(jdbcTemplate)).isEqualTo(1);

    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE account_service.account_reservations SET " + assignment))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @DisplayName("account aggregates hold exact sums beyond one reservation while retaining balance")
  @Test
  void aggregateRetainsWideRange() {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    final java.math.BigDecimal total = new java.math.BigDecimal("1844674407370955.1614");

    jdbcTemplate.update("""
        INSERT INTO account_service.account_limits (
          account_id, trading_day, currency, limit_total_notional, available_notional,
          updated_at_unix_ms) VALUES (?, DATE '2026-07-28', 'TWD', ?, ?, 1)
        """, UUID.fromString(ACCOUNT_ID), total, total);

    assertThat(jdbcTemplate.queryForObject(
            "SELECT available_notional FROM account_service.account_limits",
            java.math.BigDecimal.class))
        .isEqualByComparingTo(total);
    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE account_service.account_limits SET reserved_notional = 1"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @DisplayName("inventory uses signed long whole shares with bounded reservations")
  @Test
  void inventoryRetainsWholeShareCapacity() {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    jdbcTemplate.update("""
        INSERT INTO account_service.account_positions (
          account_id, symbol, long_qty, updated_at_unix_ms) VALUES (?, 'ABCDEFGHIJKL', ?, 1)
        """, UUID.fromString(ACCOUNT_ID), Long.MAX_VALUE);

    assertThat(jdbcTemplate.queryForObject(
            "SELECT long_qty FROM account_service.account_positions", Long.class))
        .isEqualTo(Long.MAX_VALUE);
    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE account_service.account_positions SET reserved_long_qty = -1"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @DisplayName("reservation reasons preserve all legal lifecycle outcome combinations")
  @ParameterizedTest
  @CsvSource({
      "ACCEPTED, '', ''",
      "APPLIED, '', ''",
      "REJECTED, A, Detail",
      "REJECTED, A0_, Detail",
      "REJECTED, INSUFFICIENT_CASH, Insufficient cash",
      "RELEASED, '', ''",
      "RELEASED, CANCELLED, ''",
      "RELEASED, CANCELLED, Caller detail",
      "RELEASED, caller code, ' '"
  })
  void allowsValidOutcomeReasons(String status, String code, String text) {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    assertThat(insertValidReservation(jdbcTemplate)).isOne();

    assertThat(updateOutcome(jdbcTemplate, status, code, text)).isOne();
  }

  @DisplayName("rejected reservation codes must be canonical uppercase ASCII tokens")
  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t\n", "lower", "BAD-CODE", " BAD", "BAD ", "1BAD", "失敗"})
  void rejectsMalformedRejectionCode(String code) {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    assertThat(insertValidReservation(jdbcTemplate)).isOne();
    assertThat(updateOutcome(jdbcTemplate, "REJECTED", "REJECTED", "Detail")).isOne();

    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE account_service.account_reservations SET reason_code = ?", code))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("CK_ACCOUNT_RESERVATIONS_OUTCOME_REASONS");
  }

  @DisplayName("SQL rejects empty rejection detail while the application owns blank validation")
  @Test
  void rejectsEmptyRejectionText() {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    assertThat(insertValidReservation(jdbcTemplate)).isOne();
    assertThat(updateOutcome(jdbcTemplate, "REJECTED", "REJECTED", "Detail")).isOne();

    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE account_service.account_reservations SET reason_text = ''"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("CK_ACCOUNT_RESERVATIONS_OUTCOME_REASONS");
  }

  @DisplayName("active and applied reservations cannot carry reasons")
  @ParameterizedTest
  @CsvSource({
      "ACCEPTED, reason_code", "ACCEPTED, reason_text",
      "APPLIED, reason_code", "APPLIED, reason_text"
  })
  void rejectsActiveOutcomeReason(String status, String column) {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    assertThat(insertValidReservation(jdbcTemplate)).isOne();
    assertThat(updateOutcome(jdbcTemplate, status, "", "")).isOne();

    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE account_service.account_reservations SET " + column + " = 'Unexpected'"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("CK_ACCOUNT_RESERVATIONS_OUTCOME_REASONS");
  }

  @DisplayName("SQL active reasons use exact empty strings rather than whitespace")
  @ParameterizedTest
  @ValueSource(strings = {" ", "\t\n", "\u001c", "\u2003", "\u3000"})
  void rejectsWhitespaceActiveReasons(String whitespace) {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    assertThat(insertValidReservation(jdbcTemplate)).isOne();

    for (String status : new String[] {"ACCEPTED", "APPLIED"}) {
      assertThatThrownBy(() -> updateOutcome(jdbcTemplate, status, whitespace, ""))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("CK_ACCOUNT_RESERVATIONS_OUTCOME_REASONS");
      assertThatThrownBy(() -> updateOutcome(jdbcTemplate, status, "", whitespace))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("CK_ACCOUNT_RESERVATIONS_OUTCOME_REASONS");
    }
  }

  @DisplayName("SQL accepts nonempty detail without duplicating Java Unicode blank rules")
  @ParameterizedTest
  @ValueSource(strings = {" ", "\t\n", "\u001c", "\u2003", "\u3000", "\u00a0", "\u2007", "\u202f"})
  void allowsNonemptyRejectionDetail(String detail) {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    assertThat(insertValidReservation(jdbcTemplate)).isOne();

    assertThat(updateOutcome(jdbcTemplate, "REJECTED", "REJECTED", detail)).isOne();
  }

  @DisplayName("all outcome reason columns retain NOT NULL protection")
  @ParameterizedTest
  @CsvSource({
      "ACCEPTED, reason_code", "ACCEPTED, reason_text",
      "APPLIED, reason_code", "APPLIED, reason_text",
      "REJECTED, reason_code", "REJECTED, reason_text",
      "RELEASED, reason_code", "RELEASED, reason_text"
  })
  void rejectsNullOutcomeReasons(String status, String column) {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    assertThat(insertValidReservation(jdbcTemplate)).isOne();
    final String code = status.equals("REJECTED") ? "REJECTED" : "";
    final String text = status.equals("REJECTED") ? "Detail" : "";
    assertThat(updateOutcome(jdbcTemplate, status, code, text)).isOne();

    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE account_service.account_reservations SET " + column + " = NULL"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private int updateOutcome(JdbcTemplate jdbcTemplate, String status, String code, String text) {
    final long remaining = status.equals("ACCEPTED") || status.equals("REJECTED") ? 1 : 0;
    final long filled = status.equals("APPLIED") ? 1 : 0;
    return jdbcTemplate.update("""
        UPDATE account_service.account_reservations
        SET status = ?, reason_code = ?, reason_text = ?, remaining_quantity = ?,
            filled_quantity = ?, reserved_notional = 0
        """, "RESERVATION_STATUS_" + status, code, text, remaining, filled);
  }

  private JdbcTemplate migratedDatabase() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());
    return jdbcTemplate;
  }

  private int insertValidReservation(JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.update("""
        INSERT INTO account_service.account_reservations (
          reservation_id, request_id, order_id, account_id, symbol, venue_mic, trading_day,
          side, quantity, limit_price, reserved_notional, status,
          created_at_unix_ms, updated_at_unix_ms, remaining_quantity, filled_quantity, version
        ) VALUES ('00000000-0000-0000-0000-000000000012',
          '00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000012', ?,
          '2330', 'XTAI', DATE '2026-07-28', 'SIDE_BUY', 1, 100, 100,
          'RESERVATION_STATUS_ACCEPTED', 1, 1, 1, 0, 0)
        """, UUID.fromString(ACCOUNT_ID));
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

package com.simplematch.queryservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QueryFlywayMigrationTest {
  private static final UUID ORDER_ID = UUID.fromString("0198a001-0000-7000-8000-000000000002");
  private static final UUID ACCOUNT_ID = UUID.fromString("0198a001-0000-7000-8000-000000000003");
  private JdbcTemplate jdbc;

  @BeforeEach
  void migrate() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:queryschema" + UUID.randomUUID()
        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    Flyway.configure().dataSource(dataSource)
        .locations("classpath:db/migration/query-service").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @Test
  void storesCanonicalIdentifiersAsUuidAndPreservesFullReason() {
    insertOrder();
    insertExecution();
    insertAccount();
    final String detail = "原因".repeat(600);
    jdbc.update("UPDATE query_service.account_summary_read_model "
        + "SET reason_code = ?, reason_detail = ?", "R".repeat(255), detail);

    assertThat(jdbc.queryForObject("SELECT order_id FROM query_service.order_read_model",
        UUID.class)).isEqualTo(ORDER_ID);
    assertThat(jdbc.queryForObject("SELECT account_id FROM query_service.order_read_model",
        UUID.class)).isEqualTo(ACCOUNT_ID);
    assertThat(jdbc.queryForObject("SELECT execution_id FROM query_service.execution_read_model",
        Object.class)).isInstanceOf(UUID.class);
    assertThat(jdbc.queryForObject("SELECT reason_detail "
        + "FROM query_service.account_summary_read_model", String.class)).isEqualTo(detail);
  }

  @ParameterizedTest
  @ValueSource(strings = {"order_id", "account_id"})
  void rejectsMalformedOrderIdentifiers(String column) {
    insertOrder();
    assertThatThrownBy(() -> jdbc.update("UPDATE query_service.order_read_model SET "
        + column + " = 'not-a-uuid'"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"side = 'UNKNOWN'", "state = 'UNKNOWN'",
      "venue_mic = 'XXXX'", "symbol = 'bad-symbol'", "symbol = 'ABCDEFGHIJKLM'",
      "source_partition_id = -1", "source_offset_value = -1"})
  void rejectsInvalidOrderFacts(String update) {
    insertOrder();
    assertThatThrownBy(() -> jdbc.update("UPDATE query_service.order_read_model SET " + update))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"side = 'UNKNOWN'", "venue_mic = 'XXXX'", "symbol = ''",
      "source_partition_id = -1", "source_offset_value = -1",
      "cumulative_quantity_shares = 9"})
  void rejectsInvalidExecutionFacts(String update) {
    insertExecution();
    assertThatThrownBy(() -> jdbc.update("UPDATE query_service.execution_read_model SET " + update))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"lifecycle_state = 'UNKNOWN'", "reserved_notional_units = -1"})
  void rejectsInvalidAccountFacts(String update) {
    insertAccount();
    assertThatThrownBy(() -> jdbc.update("UPDATE query_service.account_summary_read_model SET "
        + update)).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void restrictsRecoveryStatesWithoutRejectingValidGapState() {
    jdbc.update("INSERT INTO query_service.projection_checkpoint VALUES "
        + "('matching.events', 0, 1, 'READY', 1)");
    jdbc.update("UPDATE query_service.projection_checkpoint SET recovery_state = 'GAP_DETECTED'");
    assertThatThrownBy(() -> jdbc.update("UPDATE query_service.projection_checkpoint "
        + "SET recovery_state = 'UNKNOWN'"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void acceptsBothInboxIdentityFormsAndRequiresFullSha256() {
    insertInbox(ORDER_ID.toString(), 0);
    insertInbox("ab".repeat(32), 1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_service.projection_inbox",
        Integer.class)).isEqualTo(2);
    assertThatThrownBy(() -> jdbc.update("UPDATE query_service.projection_inbox "
        + "SET payload_sha256 = ?", new byte[31]))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE query_service.projection_inbox "
        + "SET payload_sha256 = ?", new byte[33]))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void bindsEachSymbolToOneVenuePerTradingDay() {
    insertReference("2026-10-15", "XTAI", "2330");
    assertThatThrownBy(() -> insertReference("2026-10-15", "ROCO", "2330"))
        .isInstanceOf(DataIntegrityViolationException.class);
    insertReference("2026-10-16", "ROCO", "2330");
    insertReference("2026-10-15", "ROCO", "ABCDEFGHIJKL");
  }

  @Test
  void rejectsUnknownVenueAndNoncanonicalReferenceSymbol() {
    assertThatThrownBy(() -> insertReference("2026-10-15", "XXXX", "2330"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertReference("2026-10-15", "XTAI", "bad-symbol"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"market_rule_id = NULL", "reference_price_units = NULL",
      "lower_price_limit_units = NULL", "upper_price_limit_units = NULL",
      "routing_partition = NULL", "market_rule_id = ''", "market_rule_id = '   '",
      "market_rule_id = '\t\n'", "reference_price_units = 0", "reference_price_units = -1",
      "lower_price_limit_units = 0", "lower_price_limit_units = -1",
      "upper_price_limit_units = 0", "upper_price_limit_units = -1",
      "lower_price_limit_units = 100", "lower_price_limit_units = 101",
      "upper_price_limit_units = 100", "upper_price_limit_units = 99",
      "routing_partition = -1", "routing_partition = 15"})
  void rejectsIncompleteOrInvalidFinalReferenceFacts(String update) {
    insertReference("2026-10-15", "XTAI", "2330");

    assertThatThrownBy(() -> jdbc.update("UPDATE query_service.active_market_reference SET "
        + update)).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void acceptsFinalReferencePriceAndRoutingBoundaries() {
    insertReference("2026-10-15", "XTAI", "2330");
    jdbc.update("UPDATE query_service.active_market_reference SET "
        + "lower_price_limit_units = 1, reference_price_units = 2, upper_price_limit_units = 3");
    jdbc.update("UPDATE query_service.active_market_reference SET routing_partition = 14, "
        + "reference_price_units = ?, upper_price_limit_units = ?",
        Long.MAX_VALUE - 1, Long.MAX_VALUE);

    assertThat(jdbc.queryForObject("SELECT routing_partition "
        + "FROM query_service.active_market_reference", Integer.class)).isEqualTo(14);
  }

  private void insertOrder() {
    jdbc.update("INSERT INTO query_service.order_read_model VALUES "
        + "(?, ?, 'XTAI', '2330', 'SIDE_BUY', 'RESTING', 10, 'event', 0, 0, 1)",
        ORDER_ID, ACCOUNT_ID);
  }

  private void insertExecution() {
    jdbc.update("INSERT INTO query_service.execution_read_model VALUES "
        + "(?, ?, ?, 'XTAI', '2330', 'SIDE_BUY', 10, 100, 10, 0, 100, 'event', 0, 0, 1)",
        UUID.randomUUID(), ORDER_ID, ACCOUNT_ID);
  }

  private void insertAccount() {
    jdbc.update("INSERT INTO query_service.account_summary_read_model VALUES "
        + "(?, 'ACCOUNT_LIFECYCLE_STATE_RESERVED', 100, 10, '', '', 'event', 1)", ACCOUNT_ID);
  }

  private void insertInbox(String eventId, long offset) {
    jdbc.update("INSERT INTO query_service.projection_inbox VALUES "
        + "(?, 'events', ?, 0, ?, 1)", eventId, new byte[32], offset);
  }

  private void insertReference(String day, String venue, String symbol) {
    jdbc.update("INSERT INTO query_service.active_market_reference "
        + "(trading_day, artifact_id, venue_mic, symbol, market_rule_id, reference_price_units, "
        + "lower_price_limit_units, upper_price_limit_units, routing_partition, "
        + "updated_at_unix_ms) "
        + "VALUES (CAST(? AS DATE), 'artifact', ?, ?, 'TW_EQ', 100, 90, 110, 0, 1)",
        day, venue, symbol);
  }
}

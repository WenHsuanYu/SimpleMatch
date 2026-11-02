package com.simplematch.riskservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.config.testsupport.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RiskServiceFlywayMigrationTest {

  @DisplayName("local resilience markers reject blank values and negative timestamps")
  @Test
  void localResilienceMarkerConstraintsRejectInvalidValues() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO risk_service.local_resilience_marker "
                        + "(run_id, marker_value, created_at_unix_ms) VALUES (?, ?, ?)",
                    " ",
                    "marker",
                    1L))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            ()
                -> jdbcTemplate.update(
                    "INSERT INTO risk_service.local_resilience_marker "
                        + "(run_id, marker_value, created_at_unix_ms) VALUES (?, ?, ?)",
                    "run-1",
                    "marker",
                    -1L))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @DisplayName("risk outbox constraints reject a negative Kafka partition")
  @Test
  void constraintsRejectNegativeOutboxPartition() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());

    assertThat(jdbcTemplate.update("""
        INSERT INTO risk_service.outbox (
          event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
          aggregate_type, aggregate_id, created_at_unix_ms, created_at
        ) VALUES (gen_random_uuid(), 'matching.commands', 'order-1', 0, decode('01', 'hex'), 'event', '{}',
          'risk_submission', 'order-1', 1, TIMESTAMP '1970-01-01 00:00:00.001')
        """)).isOne();

    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE risk_service.outbox SET kafka_partition_id = -1"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @DisplayName("rejected admission code must follow the ASCII machine token grammar")
  @ParameterizedTest
  @ValueSource(strings = {"", "invalid"})
  void rejectsInvalidRejectionCode(String code) {
    final JdbcTemplate jdbcTemplate = migratedRejection();

    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE risk_service.admission_journal SET reason_code = ?", code))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @DisplayName("rejected admission permits any uppercase ASCII token")
  @Test
  void acceptsMachineReadableRejectionCode() {
    final JdbcTemplate jdbcTemplate = migratedRejection();

    assertThat(jdbcTemplate.update(
        "UPDATE risk_service.admission_journal SET reason_code = ?", "LIMIT_2_EXCEEDED")).isOne();
  }

  @DisplayName("rejected admission detail must contain at least one character in SQL")
  @Test
  void rejectsEmptyRejectionDetail() {
    final JdbcTemplate jdbcTemplate = migratedRejection();

    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE risk_service.admission_journal SET reason_detail = ''"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @DisplayName("SQL leaves nonempty detail whitespace validation to the application")
  @Test
  void acceptsNonemptyDetailWithoutUnicodeClassification() {
    final JdbcTemplate jdbcTemplate = migratedRejection();

    assertThat(jdbcTemplate.update(
        "UPDATE risk_service.admission_journal SET reason_detail = ?", " ")).isOne();
  }

  @DisplayName("reason columns remain nonnull independently of the state check")
  @ParameterizedTest
  @ValueSource(strings = {"reason_code", "reason_detail"})
  void rejectsNullReasonColumn(String column) {
    final JdbcTemplate jdbcTemplate = migratedRejection();

    assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE risk_service.admission_journal SET " + column + " = NULL"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @DisplayName("pending and accepted admissions retain their existing reason semantics")
  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "ACCEPTED"})
  void allowsNonrejectedAdmission(String state) {
    final JdbcTemplate jdbcTemplate = migratedRejection();

    assertThat(jdbcTemplate.update("""
        UPDATE risk_service.admission_journal SET state = ?,
          reason_code = 'not a machine code', reason_detail = ' '
        """, state)).isOne();
  }

  private JdbcTemplate migratedRejection() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());
    assertThat(jdbcTemplate.update("""
        INSERT INTO risk_service.admission_journal (
          command_id, order_id, account_id, symbol, venue_mic, side, quantity,
          order_type, tif, trading_day, sender_comp_id, target_comp_id, cl_ord_id,
          routing_partition, state, reason_code, reason_detail,
          created_at_unix_ms, updated_at_unix_ms,
          artifact_trading_day, artifact_content_sha256, routing_algorithm_version
        ) VALUES (
          '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
          '00000000-0000-0000-0000-000000000002', '2330', 'XTAI', 'SIDE_BUY', 1,
          'CANCEL', 'CANCEL', DATE '2026-10-15', 'CLIENT', 'SERVER', 'cancel-1',
          0, 'REJECTED', 'INVALID_COMMAND', 'Invalid command', 1, 1,
          DATE '2026-10-15', REPEAT('a', 64), 'v1')
        """)).isOne();
    return jdbcTemplate;
  }

  private DriverManagerDataSource newDataSource() {
    return PostgresTestDatabase.create("risk-service");
  }

  private void migrate(javax.sql.DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("risk_service")
        .defaultSchema("risk_service")
        .locations("classpath:db/migration/risk-service")
        .load()
        .migrate();
  }

}

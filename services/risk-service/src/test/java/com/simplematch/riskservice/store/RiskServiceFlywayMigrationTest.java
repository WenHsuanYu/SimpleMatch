package com.simplematch.riskservice.store;

import static com.simplematch.riskservice.testsupport.H2TestDatabaseUrl.uniquePostgresModeUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RiskServiceFlywayMigrationTest {
  private static final String SCHEMA_NAME = "RISK_SERVICE";

  @DisplayName("an empty database receives the complete risk-service V1 schema")
  @Test
  void migrateEmptyDatabaseCreatesSubmissionAndOutboxTables() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());

    assertThat(hasTable(jdbcTemplate, "RISK_SUBMISSIONS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "OUTBOX")).isTrue();
    assertThat(hasTable(jdbcTemplate, "ROUTING_POLICIES")).isTrue();
    assertThat(hasTable(jdbcTemplate, "ROUTING_POLICY_ASSIGNMENTS")).isTrue();
    assertThat(hasTable(jdbcTemplate, "CONSUMER_QUARANTINES")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "OUTBOX", "CREATED_AT")).isTrue();
    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(8);
  }

  @DisplayName("a second risk-service migration is a no-op")
  @Test
  void repeatedMigrateIsNoOp() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());
    final int migrationsAfterFirstRun = appliedMigrationCount(jdbcTemplate);
    migrate(jdbcTemplate.getDataSource());

    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(migrationsAfterFirstRun);
  }

  @DisplayName("risk submission constraints reject unsupported commands")
  @Test
  void constraintsRejectUnsupportedCommand() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                                        INSERT INTO risk_service.risk_submissions (
                                          request_id, sender_comp_id, target_comp_id, trading_day, order_id, cl_ord_id,
                                          orig_cl_ord_id, raw_cl_ord_id, raw_orig_cl_ord_id, command_type, accepted,
                                          reason_code, reason_text, business_key_surrogated, created_at_unix_ms, outbox_event_id
                                        ) VALUES ('request-1', 'CLIENT', 'SIMPLEMATCH', DATE '2026-07-27', 'order-1',
                                          'clord-1', '', 'clord-1', '', 'NOT_A_COMMAND', TRUE, '', '', FALSE,
                                          1, RANDOM_UUID())
                                        """))
        .isInstanceOf(RuntimeException.class);
  }

  @DisplayName("risk outbox constraints reject a negative Kafka partition")
  @Test
  void constraintsRejectNegativeOutboxPartition() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                                        INSERT INTO risk_service.outbox (
                                          event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
                                          aggregate_type, aggregate_id, created_at_unix_ms
                                        ) VALUES (RANDOM_UUID(), 'orders.validated', 'order-1', -1, X'01', 'event', '{}',
                                          'risk_submission', 'order-1', 1)
                                        """))
        .isInstanceOf(RuntimeException.class);
  }

  private DriverManagerDataSource newDataSource() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(uniquePostgresModeUrl());
    return dataSource;
  }

  private void migrate(javax.sql.DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/risk-service")
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

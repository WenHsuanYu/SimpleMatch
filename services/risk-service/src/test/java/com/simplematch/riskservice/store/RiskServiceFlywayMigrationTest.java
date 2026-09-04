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

  @DisplayName("an empty database receives only the active risk-service schema")
  @Test
  void migrateEmptyDatabaseCreatesOnlyActiveTables() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());

    assertThat(hasTable(jdbcTemplate, "RISK_SUBMISSIONS")).isFalse();
    assertThat(hasTable(jdbcTemplate, "OUTBOX")).isTrue();
    assertThat(hasTable(jdbcTemplate, "ROUTING_POLICIES")).isFalse();
    assertThat(hasTable(jdbcTemplate, "ROUTING_POLICY_ASSIGNMENTS")).isFalse();
    assertThat(hasTable(jdbcTemplate, "CONSUMER_QUARANTINES")).isFalse();
    assertThat(hasColumn(jdbcTemplate, "OUTBOX", "CREATED_AT")).isTrue();
    assertThat(hasTable(jdbcTemplate, "CDC_DELIVERY_OBSERVATION")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "ADMISSION_JOURNAL", "ARTIFACT_TRADING_DAY")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "ADMISSION_JOURNAL", "ARTIFACT_CONTENT_SHA256")).isTrue();
    assertThat(hasColumn(jdbcTemplate, "ADMISSION_JOURNAL", "ROUTING_POLICY_ID")).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM risk_service.cdc_delivery_lag", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT metric_name FROM risk_service.cdc_delivery_lag", String.class))
        .isEqualTo("matching.commands");
    assertThat(columnNullable(jdbcTemplate, "ADMISSION_JOURNAL", "ROUTING_PARTITION"))
        .isEqualTo("NO");
    assertThat(columnNullable(jdbcTemplate, "ADMISSION_JOURNAL", "ARTIFACT_TRADING_DAY"))
        .isEqualTo("NO");
    assertThat(columnNullable(jdbcTemplate, "ADMISSION_JOURNAL", "ARTIFACT_CONTENT_SHA256"))
        .isEqualTo("NO");
    assertThat(columnNullable(jdbcTemplate, "ADMISSION_JOURNAL", "ROUTING_ALGORITHM_VERSION"))
        .isEqualTo("NO");
    assertThat(appliedMigrationCount(jdbcTemplate)).isEqualTo(7);
  }

  @DisplayName("CDC observation columns preserve Kafka and epoch value widths")
  @Test
  void cdcObservationColumnsPreserveKafkaAndEpochValueWidths() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());

    migrate(jdbcTemplate.getDataSource());

    assertThat(columnDataType(jdbcTemplate, "CDC_DELIVERY_OBSERVATION", "EVENT_ID"))
        .isEqualTo("UUID");
    assertThat(columnDataType(jdbcTemplate, "CDC_DELIVERY_OBSERVATION", "TOPIC"))
        .isEqualTo("CHARACTER VARYING");
    assertThat(columnLength(jdbcTemplate, "CDC_DELIVERY_OBSERVATION", "TOPIC")).isEqualTo(255);
    assertThat(columnDataType(jdbcTemplate, "CDC_DELIVERY_OBSERVATION", "PARTITION_ID"))
        .isEqualTo("INTEGER");
    assertThat(columnDataType(jdbcTemplate, "CDC_DELIVERY_OBSERVATION", "KAFKA_OFFSET"))
        .isEqualTo("BIGINT");
    assertThat(columnDataType(jdbcTemplate, "CDC_DELIVERY_OBSERVATION", "OBSERVED_AT_UNIX_MS"))
        .isEqualTo("BIGINT");
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
                                        ) VALUES (RANDOM_UUID(), 'matching.commands', 'order-1', -1, X'01', 'event', '{}',
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

  private String columnDataType(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
    return jdbcTemplate.queryForObject(
        """
                        SELECT UPPER(DATA_TYPE)
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE UPPER(TABLE_SCHEMA) = ?
                          AND UPPER(TABLE_NAME) = ?
                          AND UPPER(COLUMN_NAME) = ?
                        """,
        String.class,
        SCHEMA_NAME,
        tableName,
        columnName);
  }

  private int columnLength(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
    return jdbcTemplate.queryForObject(
        """
                        SELECT CHARACTER_MAXIMUM_LENGTH
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE UPPER(TABLE_SCHEMA) = ?
                          AND UPPER(TABLE_NAME) = ?
                          AND UPPER(COLUMN_NAME) = ?
                        """,
        Integer.class,
        SCHEMA_NAME,
        tableName,
        columnName);
  }

  private String columnNullable(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
    return jdbcTemplate.queryForObject(
        """
                        SELECT UPPER(IS_NULLABLE)
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE UPPER(TABLE_SCHEMA) = ?
                          AND UPPER(TABLE_NAME) = ?
                          AND UPPER(COLUMN_NAME) = ?
                        """,
        String.class,
        SCHEMA_NAME,
        tableName,
        columnName);
  }
}

package com.simplematch.riskservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcOutboxRepositoryTest {
  private static final String EVENT_ID_ONE = "00000000-0000-7000-8000-000000000001";

  private JdbcTemplate jdbcTemplate;
  private OutboxRepository repository;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS risk_service\\;SET SCHEMA risk_service");
    jdbcTemplate = new JdbcTemplate(dataSource);
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/risk-service")
        .load()
        .migrate();
    repository = new JdbcOutboxRepository(jdbcTemplate);
  }

  @Test
  void insertsOutboxRecord() {
    final OutboxRecord record = outboxRecord(EVENT_ID_ONE);

    repository.insert(record);

    final OutboxRecord stored =
        jdbcTemplate.queryForObject(
            """
                        SELECT event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
                               aggregate_type, aggregate_id, created_at_unix_ms
                        FROM outbox
                        WHERE event_id = ?
                        """,
            (resultSet, rowNum) ->
                OutboxRecord.create(
                    new OutboxRecord.EventInfo(
                        resultSet.getObject("event_id", UUID.class).toString(),
                        resultSet.getLong("created_at_unix_ms")),
                    OutboxRecord.Routing.of(
                        resultSet.getString("topic"),
                        resultSet.getString("message_key"),
                        resultSet.getObject("kafka_partition_id", Integer.class)),
                    new OutboxRecord.PayloadEnvelope(
                        resultSet.getBytes("payload"),
                        resultSet.getString("payload_type"),
                        resultSet.getString("headers_json")),
                    new OutboxRecord.AggregateRef(
                        resultSet.getString("aggregate_type"),
                        resultSet.getString("aggregate_id"))),
            UUID.fromString(record.eventId()));

    assertThat(stored.eventId()).isEqualTo(record.eventId());
    assertThat(stored.topic()).isEqualTo(record.topic());
    assertThat(stored.messageKey()).isEqualTo(record.messageKey());
    assertThat(stored.kafkaPartitionId()).isEqualTo(record.kafkaPartitionId());
    assertThat(stored.payload()).containsExactly(record.payload());
    assertThat(stored.payloadType()).isEqualTo(record.payloadType());
    assertThat(stored.headersJson()).isEqualTo(record.headersJson());
    assertThat(stored.aggregateType()).isEqualTo(record.aggregateType());
    assertThat(stored.aggregateId()).isEqualTo(record.aggregateId());
    assertThat(stored.createdAtUnixMs()).isEqualTo(record.createdAtUnixMs());
  }

  @Test
  void throwsDuplicateKeyWhenEventIdAlreadyExists() {
    final OutboxRecord record = outboxRecord(EVENT_ID_ONE);
    repository.insert(record);

    assertThatThrownBy(() -> repository.insert(outboxRecord(EVENT_ID_ONE)))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(countRows("outbox")).isEqualTo(1);
  }

  private OutboxRecord outboxRecord(String eventId) {
    return OutboxRecord.create(
        new OutboxRecord.EventInfo(eventId, 100L),
        OutboxRecord.Routing.withPartition("orders.validated", "AAPL", 7),
        new OutboxRecord.PayloadEnvelope(
            new byte[] {1, 2, 3},
            "com.simplematch.contracts.orders.v1.OrderValidated",
            "{\"event_id\":\"" + eventId + "\"}"),
        new OutboxRecord.AggregateRef("risk_submission", "O-C1"));
  }

  private int countRows(String tableName) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
  }
}

package com.simplematch.riskservice.store;

import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter that stores risk-service outbox rows. */
public final class JdbcOutboxRepository implements OutboxRepository {
  private final JdbcTemplate jdbcTemplate;

  /** Creates the repository with the risk-service data source. */
  public JdbcOutboxRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
  }

  @Override
  public void insert(OutboxRecord record) {
    insertRecord(record, "");
  }

  @Override
  public boolean insertIfAbsent(OutboxRecord record) {
    if (isPostgres()) {
      return insertRecord(record, " ON CONFLICT (event_id) DO NOTHING") == 1;
    }
    final UUID eventId = UUID.fromString(record.eventInfo().eventId());
    final Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM risk_service.outbox WHERE event_id = ?", Integer.class, eventId);
    if (count != null && count > 0) {
      return false;
    }
    insertRecord(record, "");
    return true;
  }

  private int insertRecord(OutboxRecord record, String suffix) {
    final OutboxRecord.EventInfo eventInfo = record.eventInfo();
    final OutboxRecord.Routing routing = record.routing();
    final OutboxRecord.PayloadEnvelope payloadEnvelope = record.payloadEnvelope();
    final OutboxRecord.AggregateRef aggregateReference = record.aggregateReference();

    return jdbcTemplate.update(
        """
        INSERT INTO risk_service.outbox (
            event_id,
            topic,
            message_key,
            kafka_partition_id,
            payload,
            payload_type,
            headers_json,
            aggregate_type,
            aggregate_id,
            created_at_unix_ms,
            created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
            + suffix,
        UUID.fromString(eventInfo.eventId()),
        routing.topic(),
        routing.messageKey(),
        routing.kafkaPartitionId(),
        payloadEnvelope.payload(),
        payloadEnvelope.payloadType(),
        payloadEnvelope.headersJson(),
        aggregateReference.aggregateType(),
        aggregateReference.aggregateId(),
        eventInfo.createdAtUnixMs(),
        Timestamp.from(java.time.Instant.ofEpochMilli(eventInfo.createdAtUnixMs())));
  }

  private boolean isPostgres() {
    return Objects.requireNonNull(
        jdbcTemplate.execute(
            (ConnectionCallback<Boolean>)
                connection ->
                    connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")),
        "database product name");
  }
}

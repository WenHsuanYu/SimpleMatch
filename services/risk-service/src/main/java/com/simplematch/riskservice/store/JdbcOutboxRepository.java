package com.simplematch.riskservice.store;

import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import java.util.Objects;
import java.util.UUID;
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
    jdbcTemplate.update(
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
            created_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.fromString(record.eventId()),
        record.topic(),
        record.messageKey(),
        record.kafkaPartitionId(),
        record.payload(),
        record.payloadType(),
        record.headersJson(),
        record.aggregateType(),
        record.aggregateId(),
        record.createdAtUnixMs());
  }
}

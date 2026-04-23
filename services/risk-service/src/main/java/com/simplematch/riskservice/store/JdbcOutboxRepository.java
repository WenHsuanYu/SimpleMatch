package com.simplematch.riskservice.store;

import com.simplematch.riskservice.submission.OutboxRecord;
import com.simplematch.riskservice.submission.OutboxRepository;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcOutboxRepository implements OutboxRepository {
  private final JdbcTemplate jdbcTemplate;

  public JdbcOutboxRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
  }

  @Override
  public void insert(OutboxRecord record) {
    jdbcTemplate.update(
        """
            INSERT INTO outbox (
              event_id,
              topic,
              message_key,
              payload,
              payload_type,
              headers_json,
              aggregate_type,
              aggregate_id,
              created_at_unix_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        record.eventId(),
        record.topic(),
        record.messageKey(),
        record.payload(),
        record.payloadType(),
        record.headersJson(),
        record.aggregateType(),
        record.aggregateId(),
        record.createdAtUnixMs());
  }
}